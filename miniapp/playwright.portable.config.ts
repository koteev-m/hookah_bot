import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  testMatch: 'portable-release.spec.ts',
  outputDir: '../.tmp-checks/playwright-portable',
  timeout: 30_000,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: { trace: 'retain-on-failure', serviceWorkers: 'block', ignoreHTTPSErrors: true },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'webkit', use: { ...devices['iPhone 13'] } }
  ]
})
