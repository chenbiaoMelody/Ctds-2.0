import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// WBS-2.4.9 前端框架骨架（H1/H8/H9）：
// - dev server 默认 5173；/api 前缀代理到 example-service（8080），供标准能力示例页真实调用 2.4.8 演示端点
// - vitest 单测环境 = jsdom（组件渲染/守卫行为断言用）
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    pool: 'threads',
  },
})
