import { defineConfig } from 'wxt';

// Nudge for Chrome — MV3 config.
// Architecture: ops/routes/nudge/research/ext-08-architecture.md
// MV3 recipes:  ops/routes/nudge/research/ext-01-mv3-architecture.md
export default defineConfig({
  srcDir: 'src',
  modules: ['@wxt-dev/module-react'],
  // Extension pages load from disk, so modulepreload buys nothing — and Chrome logs a
  // "cross-world extension resource mismatch" warning for every preloaded chunk, ~6 per page.
  // A clean console is part of a zero-telemetry product's trust story; better to drop the
  // useless hints than to teach users that warnings here are normal.
  vite: () => ({
    build: { modulePreload: false },
  }),
  manifest: {
    name: 'Nudge — Website Blocker, Screen Time & Shorts Blocker',
    description:
      'Block distracting sites with friction instead of walls: delay-to-open, breathing pauses, daily time limits and local-only screen time. No account, no telemetry.',
    permissions: [
      'declarativeNetRequest',
      'tabs',
      'storage',
      'unlimitedStorage',
      'alarms',
      'idle',
      // Gray-screen mode registers grayscale.css as a DYNAMIC content script so it can be
      // turned off AND still inject before first paint. No install-time warning.
      'scripting',
    ],
    host_permissions: ['<all_urls>'],
    // A DNR `redirect.extensionPath` target MUST be web-accessible or the redirect
    // silently fails — even though the extension owns the page.
    // See ext-01 §1 (w3c/webextensions#604).
    web_accessible_resources: [
      {
        resources: ['blocked.html'],
        matches: ['<all_urls>'],
      },
    ],
    action: {
      default_title: 'Nudge',
    },
    // The full-tab dashboard IS the options page (PRD item 9), so right-clicking the
    // toolbar icon -> Options lands on it instead of going nowhere.
    options_page: 'dashboard.html',
  },
});
