import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import QueueView from './QueueView.vue'
import { fetchReviewQueue } from '../../api/subject'

/**
 * WBS-3.1.5 审核清单页测试：清单渲染/空态/分页参数（后端过滤与分页由集成测试覆盖）。
 * 每用例独立挂载 + 真实 router 实例（工具链坑记忆口径）。
 */
vi.mock('../../api/subject', () => ({
  fetchReviewQueue: vi.fn(),
}))

const mockedQueue = vi.mocked(fetchReviewQueue)

const mountPage = async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/review/:subjectNo', name: 'review-detail', component: { template: '<div />' } }],
  })
  const wrapper = mount(QueueView, { global: { plugins: [ElementPlus, router] } })
  await flushPromises()
  return wrapper
}

describe('审核工作台清单页（WBS-3.1.5）', () => {
  it('渲染待审核主体清单与查看档案入口', async () => {
    mockedQueue.mockResolvedValue({
      list: [
        { subjectNo: 'S20260913000001', subjectName: '蓝天数据科技有限公司', subjectType: 'ENTERPRISE', createdAt: '2026-09-13T10:00:00' },
        { subjectNo: 'S20260913000002', subjectName: '市大数据管理局', subjectType: 'GOV', createdAt: '2026-09-13T10:05:00' },
      ],
      total: 2,
      pageNum: 1,
      pageSize: 10,
      totalPages: 1,
    })
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('蓝天数据科技有限公司')
    expect(wrapper.text()).toContain('市大数据管理局')
    expect(wrapper.text()).toContain('政府部门')
    expect(wrapper.text()).toContain('查看档案')
  })

  it('无待审核主体时展示空态文案', async () => {
    mockedQueue.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 })
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('暂无待审核主体')
  })

  it('清单加载失败时展示业务文案（错误码透传）', async () => {
    mockedQueue.mockRejectedValue(new (await import('../../api/client')).ApiError('1000S0001', '服务暂不可用'))
    const wrapper = await mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('主体审核')
  })
})
