import { describe, it, expect } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import IndexView from './IndexView.vue'

/**
 * WBS-2.4.9 H7 工作台示例页测试：
 * 统计卡片 ×3、静态能力表格、组件示例（按钮/输入框）、权限演示提示条渲染断言。
 * 每用例独立挂载，避免共享 wrapper 状态。
 */
const mountPage = () =>
  mount(IndexView, {
    global: { plugins: [ElementPlus] },
  })

describe('工作台示例页（H7）', () => {
  it('渲染页面标题与描述', () => {
    const wrapper = mountPage()
    expect(wrapper.find('.page-title').text()).toBe('工作台')
    expect(wrapper.find('.page-desc').exists()).toBe(true)
  })

  it('渲染 3 张统计卡片（标准能力域/已开放能力/示例页面）', () => {
    const wrapper = mountPage()
    const cards = wrapper.findAll('.stat-num')
    expect(cards.length).toBe(3)
    expect(wrapper.text()).toContain('标准能力域')
    expect(wrapper.text()).toContain('已开放能力')
  })

  it('渲染静态标准能力表格（3 行未开放）', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const rows = wrapper.findAll('.el-table__row')
    expect(rows.length).toBe(3)
    expect(wrapper.text()).toContain('未开放')
  })

  it('渲染权限演示提示条（角色切换说明）', () => {
    const wrapper = mountPage()
    expect(wrapper.find('.demo-tip').exists()).toBe(true)
    expect(wrapper.text()).toContain('仅管理员可见')
  })

  it('渲染 Element Plus 组件示例（按钮/输入框）', () => {
    const wrapper = mountPage()
    expect(wrapper.findAll('.el-button').length).toBeGreaterThanOrEqual(2)
    expect(wrapper.find('.el-input').exists()).toBe(true)
  })
})
