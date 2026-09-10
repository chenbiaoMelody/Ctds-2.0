import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import IndexView from './IndexView.vue'

/**
 * WBS-2.4.9 H4 权限演示页测试：页面文案渲染冒烟（实际守卫拦截行为由 router.spec 权限点单测覆盖）。
 */
describe('仅管理员可见页（H4）', () => {
  const wrapper = mount(IndexView, {
    global: { plugins: [ElementPlus] },
  })

  it('渲染权限演示文案', () => {
    expect(wrapper.find('.page-title').text()).toBe('仅管理员可见')
    expect(wrapper.text()).toContain('权限校验通过')
  })
})
