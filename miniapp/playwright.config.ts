import { defineConfig, devices } from '@playwright/test'

const port = Number(process.env.MINIAPP_E2E_PORT ?? 5173)
const baseURL = `http://127.0.0.1:${port}/miniapp/`
const reuseExistingServer = process.env.MINIAPP_E2E_REUSE_EXISTING === 'true' && !process.env.CI

export default defineConfig({
  testDir: './e2e',
  outputDir: '../.tmp-checks/playwright',
  timeout: 30_000,
  expect: {
    timeout: 5_000
  },
  reporter: [['list']],
  use: {
    baseURL,
    trace: 'retain-on-failure'
  },
  webServer: {
    command: `VITE_BACKEND_PUBLIC_URL=http://127.0.0.1:${port} npm run dev -- --host 127.0.0.1 --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer,
    timeout: 60_000
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
})
