import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// WBS-2.4.9 前端框架骨架（H1/H8/H9）：
// - dev server 默认 5173；/api 前缀代理到 example-service（8080），供标准能力示例页真实调用 2.4.8 演示端点
// - vitest 单测环境 = jsdom（组件渲染/守卫行为断言用）
// WBS-2.4.12：E2E（e2e/*.spec.ts）归 Playwright 执行，vitest exclude 排除（默认 exclude 保留）
import { defaultExclude } from 'vitest/config'
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      // DID 服务（8082）独立端口：按前缀优先转发（键顺序在前，先于通用 /api 匹配）。
      // WBS-3.1.11 实施补充：界面走查需在浏览器经 5173 访问 DID 服务，属开发期转发配置，
      // 不改任何服务契约与门禁配置。
      '/api/v1/did': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    pool: 'threads',
    exclude: [...defaultExclude, 'e2e/**'],
    // WBS-3.1.6 测试环境修复：el-form 校验在 vitest SSR 下假通过——element-plus 被外置后，
    // 其 'async-validator' 裸导入走 Node CJS interop 拿到整个 module.exports 对象，
    // new Schema() 抛 TypeError 且被 EP 的 catch(fields) 吞成通过。将两者内联交 vite 解析
    // （走 async-validator 的 module 字段 = dist-web ESM，与浏览器构建一致），校验行为即真实。
    server: { deps: { inline: [/element-plus/, /async-validator/] } },
  },
})
