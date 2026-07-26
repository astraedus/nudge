import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  // Extension state (DNR rules, storage) is global to the browser context, so specs must
  // not race each other.
  fullyParallel: false,
  workers: 1,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    trace: 'retain-on-failure',
    video: 'off',
  },
});
