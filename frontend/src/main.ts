import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import './styles/theme.css'

/**
 * WBS-2.4.9 H1/H5 应用入口：
 * - Element Plus 全量注册 + 图标全量注册（骨架期简单直接，业务页即用即得）；
 * - 路由（H3/H4）、主题（H6）。
 */
const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(ElementPlus)
app.use(router)
app.mount('#app')
