import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

export default tseslint.config(
  {
    ignores: [
      '.output/**',
      '.wxt/**',
      'node_modules/**',
      'coverage/**',
      'playwright-report/**',
      'test-results/**',
      // Scratch tooling the QA agent drops into this directory during live runs.
      // Untracked and never shipped, but it would otherwise fail the local lint gate.
      '.qa-*',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    languageOptions: {
      globals: { ...globals.browser, ...globals.webextensions },
    },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // `rules-of-hooks` stays ON and is load-bearing: it caught a real bug where
      // DelayView/BreathingView returned early before calling their hooks.
      //
      // These three, however, are React Compiler *optimization* advisories rather than
      // correctness rules — they flag patterns (load-on-mount effects, hand-written
      // useCallback deps) that are correct but that the compiler would rather own. Turned
      // off deliberately; revisit if this codebase ever adopts the compiler.
      'react-hooks/set-state-in-effect': 'off',
      'react-hooks/preserve-manual-memoization': 'off',
      'react-hooks/use-memo': 'off',
      // Unused vars are a real signal here (a dropped prop, a dead handler), but an
      // intentionally-ignored argument should still be expressible.
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      // MV3 forbids eval outright, and the extension is zero-network by design —
      // these rules make both promises enforceable rather than aspirational.
      'no-eval': 'error',
      'no-implied-eval': 'error',
      'no-restricted-globals': [
        'error',
        {
          name: 'fetch',
          message:
            'Nudge makes zero network requests — that is a listed privacy claim. See extension/CLAUDE.md.',
        },
      ],
    },
  },
  {
    // Node-side tooling: configs and the Playwright suite.
    files: ['*.config.ts', 'e2e/**/*.ts'],
    languageOptions: { globals: { ...globals.node, ...globals.webextensions } },
    rules: {
      // Playwright's fixture API hands each fixture a `use()` callback, which the hooks
      // plugin mistakes for React's `use` hook. There is no React here.
      'react-hooks/rules-of-hooks': 'off',
    },
  },
);
