import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.PLAYWRIGHT_BASE_URL;
if (!baseURL) throw new Error('PLAYWRIGHT_BASE_URL must be set for Aceso browser acceptance tests');
const executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH;

/**
 * Playwright 配置 — Aceso 浏览器验收测试。
 *
 * 仅从必填的 PLAYWRIGHT_BASE_URL 读取用户已启动的 Aceso 地址；
 * 不得配置 webServer，不得启动或停止任何用户管理的服务。
 *
 * 窄屏项目不使用 Pixel 5 触摸仿真：本产品移动端布局（固定 260px 侧边栏）
 * 会令布局视口超出设备宽度，Chromium isMobile 自动缩放 + 视觉视口平移导致
 * 触点坐标漂移（https://github.com/microsoft/playwright/issues 同类问题）。
 * 改为同宽非触摸视口，验证窄屏响应式（断点、表格横向滚动、元素不重叠）。
 */
export default defineConfig({
  testDir: './apps/aceso/e2e',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL,
    launchOptions: executablePath ? { executablePath } : undefined,
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
      use: {
        // 393×851 ≈ Pixel 5 CSS 尺寸，但关闭 isMobile/hasTouch：避免移动端自动缩放
        viewport: { width: 393, height: 851 },
        deviceScaleFactor: 1,
      },
    },
  ],
});
