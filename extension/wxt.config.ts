import { defineConfig } from 'wxt';

// Nudge for Chrome — MV3 config.
// Architecture: ops/routes/nudge/research/ext-08-architecture.md
// MV3 recipes:  ops/routes/nudge/research/ext-01-mv3-architecture.md
export default defineConfig({
  srcDir: 'src',
  modules: ['@wxt-dev/module-react'],
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
  },
});
