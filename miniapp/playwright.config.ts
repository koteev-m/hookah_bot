import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  outputDir: '../.tmp-checks/playwright',
  timeout: 30_000,
  expect: {
    timeout: 5_000
  },
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:5173/miniapp/',
    trace: 'retain-on-failure'
  },
  webServer: {
    command: 'VITE_BACKEND_PUBLIC_URL=http://127.0.0.1:5173 npm run dev -- --host 127.0.0.1',
    url: 'http://127.0.0.1:5173/miniapp/',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
})
