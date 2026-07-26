import {
  ensureAlarms,
  handleAlarm,
  scheduleLightsOffBoundaries,
} from '../background/alarmsHub';
import { applyRules } from '../background/dnr';
import { applyGrayscale } from '../background/grayscale';
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
 *
 * `now` is threaded explicitly through both re-derivation paths (bootstrap and the settings
 * listener) because Lights Off makes the DNR rule set a function of the CLOCK as well as of
 * settings: a worker waking at 23:00 must compile a lockdown that a worker waking at noon
 * must not. Letting each layer read its own clock would work today and rot the moment one of
 * them is tested or called with a fixed time.
 */
export default defineBackground(() => {
  registerMessageRouter();

  async function bootstrap(): Promise<void> {
    try {
      await chrome.idle.setDetectionInterval(IDLE_DETECTION_SECONDS);
      const now = new Date();
      const settings = await loadSettings();
      await applyRules(settings, now);
      // Gray-screen's content-script registration persists across sessions, so it is
      // re-derived from settings here rather than only on change, otherwise a stale
      // registration could outlive the setting that asked for it.
      await applyGrayscale(settings.globalEnabled && settings.youtube.grayScreen);
      await ensureAlarms(settings, now);
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
      void loadSettings().then(async (settings) => {
        const now = new Date();
        await applyRules(settings, now);
        await applyGrayscale(settings.globalEnabled && settings.youtube.grayScreen);
        // A Lights Off schedule edit moves the boundaries, so the alarms have to move with
        // it — otherwise the window would only start on the next heartbeat that noticed.
        await scheduleLightsOffBoundaries(settings, now);
      });
    }
  });

  void bootstrap();
});
