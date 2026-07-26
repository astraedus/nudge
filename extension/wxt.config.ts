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
    // CWS caps manifest.description at 132 characters; the previous value was 160 and would
    // have been rejected or silently truncated at submit. Verbatim from the verified listing
    // copy: ops/routes/nudge/research/ext-09-listing-package/listing-copy.md (130 chars).
    description:
      'Friction, not walls: delay and breathing pauses block distracting sites. Daily limits, local screen time. No account, no tracking.',
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
      // Chrome would fall back to `icons`, but naming the small sizes explicitly keeps the
      // toolbar button crisp instead of letting it downscale the 128 itself.
      default_icon: {
        16: 'icon/16.png',
        32: 'icon/32.png',
        48: 'icon/48.png',
        128: 'icon/128.png',
      },
    },
    // The full-tab dashboard IS the options page (PRD item 9), so right-clicking the
    // toolbar icon -> Options lands on it instead of going nowhere.
    options_page: 'dashboard.html',
  },
});
