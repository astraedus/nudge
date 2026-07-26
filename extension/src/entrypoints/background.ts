import { ensureAlarms, handleAlarm } from '../background/alarmsHub';
import { applyRules } from '../background/dnr';
import { registerMessageRouter } from '../background/messagesRouter';
import { loadSettings } from '../background/storage';
import { IDLE_DETECTION_SECONDS, onActivityEvent } from '../background/tracker';

/**
 * Service-worker entrypoint.
 *
 * Every listener is registered SYNCHRONOUSLY at the top level. An MV3 worker is torn down
 * after ~30s idle and re-spawned by an incoming event; a listener registered inside an
 * async callback would not exist yet when that event arrives, so the event is missed.
 *
 * `bootstrap()` therefore runs on every worker wake (not just on install) and re-derives
 * all durable state — DNR rules, standing alarms, live temp-allow grants — because none of
 * it can be assumed to have survived.
 */
export default defineBackground(() => {
  registerMessageRouter();

  async function bootstrap(): Promise<void> {
    try {
      await chrome.idle.setDetectionInterval(IDLE_DETECTION_SECONDS);
      await applyRules(await loadSettings());
      await ensureAlarms();
      await onActivityEvent();
    } catch (error) {
      console.error('[nudge] bootstrap failed', error);
    }
  }

  chrome.runtime.onInstalled.addListener((details) => {
    void bootstrap();
    if (details.reason === 'install') {
      void chrome.tabs.create({ url: chrome.runtime.getURL('onboarding.html') });
    }
  });

  chrome.runtime.onStartup.addListener(() => void bootstrap());

  // --- Usage accounting. Each of these is "something about attention changed" ---
  chrome.tabs.onActivated.addListener(() => void onActivityEvent());
  chrome.tabs.onUpdated.addListener((_tabId, changeInfo) => {
    // Only a committed URL change matters; ignore title/favicon churn.
    if (changeInfo.url !== undefined) void onActivityEvent();
  });
  // Fires with WINDOW_ID_NONE when Chrome itself loses OS focus, which is what stops us
  // counting time while the user is in another application.
  chrome.windows.onFocusChanged.addListener(() => void onActivityEvent());
  chrome.idle.onStateChanged.addListener(() => void onActivityEvent());

  chrome.alarms.onAlarm.addListener((alarm) => void handleAlarm(alarm));

  // Settings can change from any surface (and from another synced device) — recompile.
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'session') return;
    if (Object.keys(changes).some((key) => key === 'nudge:settings')) {
      void loadSettings().then(applyRules);
    }
  });

  void bootstrap();
});
