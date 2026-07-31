import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright 配置 — Aceso 养老院入住照护周期生命周期验收测试。
 *
 * 仅从必填的 PLAYWRIGHT_BASE_URL 读取用户已启动的 Aceso 地址；
 * 不得配置 webServer，不得启动或停止任何用户管理的服务。
 */
export default defineConfig({
  testDir: './apps/aceso/e2e',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:4324',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'mobile-chrome',
      use: { ...devices['Pixel 5'] },
    },
  ],
});
