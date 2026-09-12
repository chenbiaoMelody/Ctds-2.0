import { defineConfig, devices } from '@playwright/test'

// WBS-2.4.12 E2E 配置（hifi B10，规范载体 ADR-011）：
// - webServer 自动起停 vite dev（5173）——npm run e2e 一条命令闭环，测试完自动关停；
// - 仅 Chromium 最小实现（多浏览器矩阵留观察项，ADR-011）；
// - 失败留痕（trace/screenshot）落 test-results/（gitignore，不入库）；
// - 5173 被占用时 webServer 探测失败属预期行为（hifi 边界值 B-5），先释放端口再跑。
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: true,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
