import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import IndexView from './IndexView.vue'

/**
 * WBS-2.4.9 H4 权限演示页测试：页面文案渲染冒烟（实际守卫拦截行为由 router.spec 权限点单测覆盖）。
 * 每用例独立挂载，避免共享 wrapper 状态。
 */
const mountPage = () =>
  mount(IndexView, {
    global: { plugins: [ElementPlus] },
  })

describe('仅管理员可见页（H4）', () => {
  it('渲染权限演示文案', () => {
    const wrapper = mountPage()
    expect(wrapper.find('.page-title').text()).toBe('仅管理员可见')
    expect(wrapper.text()).toContain('权限校验通过')
  })
})
