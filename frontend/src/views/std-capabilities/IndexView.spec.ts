import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import IndexView from './IndexView.vue'

/**
 * WBS-2.4.9 H8 标准能力示例页测试（mock fetch）：
 * - 成功：渲染三行"未开放"能力域；
 * - 失败：显示错误提示与重试按钮；
 * - 空数组：显示空态提示。
 */

const okBody = {
  code: '0',
  traceId: 't-1',
  data: [
    { code: 'interconnect', name: '互联互通', implemented: false, message: '尚未开放' },
    { code: 'did', name: '跨空间身份互认', implemented: false, message: '尚未开放' },
    { code: 'evidence', name: '测评证据', implemented: false, message: '尚未开放' },
  ],
}

const emptyBody = { code: '0', traceId: 't-2', data: [] }

const mountPage = () =>
  mount(IndexView, {
    global: { plugins: [ElementPlus] },
  })

describe('标准能力示例页（H8）', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('成功：渲染三行能力域且状态为未开放', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ json: () => Promise.resolve(okBody) }))
    const wrapper = mountPage()
    await flushPromises()
    const rows = wrapper.findAll('.el-table__row')
    expect(rows.length).toBe(3)
    expect(wrapper.text()).toContain('互联互通')
    expect(wrapper.text()).toContain('跨空间身份互认')
    expect(wrapper.text()).toContain('测评证据')
    expect(wrapper.text()).toContain('未开放')
  })

  it('失败：显示错误提示与重试按钮', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')))
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('.el-alert').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂不可用')
    expect(wrapper.text()).toContain('重试')
  })

  it('空数组：显示空态提示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ json: () => Promise.resolve(emptyBody) }))
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无标准能力域')
  })

  it('封套 code 非 0：按错误提示处理', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ json: () => Promise.resolve({ code: '1', message: 'x' }) }))
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('.el-alert').exists()).toBe(true)
    expect(wrapper.text()).toContain('返回异常')
  })
})
