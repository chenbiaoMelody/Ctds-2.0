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

  it('清单加载失败时展示业务文案（错误码透传，删掉错误分支本用例必红）', async () => {
    const { ApiError } = await import('../../api/client')
    mockedQueue.mockRejectedValue(new ApiError('1000S0001', '认证服务暂不可用请稍后重试'))
    await mountPage()
    await flushPromises()
    expect(document.body.textContent).toContain('认证服务暂不可用请稍后重试')
  })

  it('清单渲染顺序 = 接口返回的申请时间升序（WBS-3.1.6 T3 前端侧：前端不得本地重排）', async () => {
    mockedQueue.mockResolvedValue({
      list: [
        { subjectNo: 'S20260913000001', subjectName: '最早申请', subjectType: 'ENTERPRISE', createdAt: '2026-09-13T08:00:00' },
        { subjectNo: 'S20260913000002', subjectName: '其次', subjectType: 'GOV', createdAt: '2026-09-13T12:00:00' },
        { subjectNo: 'S20260913000003', subjectName: '最晚申请', subjectType: 'INSTITUTION', createdAt: '2026-09-13T18:00:00' },
      ],
      total: 3,
      pageNum: 1,
      pageSize: 10,
      totalPages: 1,
    })
    const wrapper = await mountPage()
    const text = wrapper.text()
    expect(text.indexOf('最早申请')).toBeLessThan(text.indexOf('其次'))
    expect(text.indexOf('其次')).toBeLessThan(text.indexOf('最晚申请'))
  })

  it('翻页按新页码重新拉取并以返回数据替换渲染（WBS-3.1.6 T3 分页参数透传）', async () => {
    mockedQueue.mockResolvedValue({
      list: [
        { subjectNo: 'S20260913000001', subjectName: '第一页主体', subjectType: 'ENTERPRISE', createdAt: '2026-09-13T10:00:00' },
      ],
      total: 11,
      pageNum: 1,
      pageSize: 10,
      totalPages: 2,
    })
    const wrapper = await mountPage()
    expect(mockedQueue).toHaveBeenCalledWith(1, 10)
    mockedQueue.mockResolvedValue({
      list: [
        { subjectNo: 'S20260913000011', subjectName: '第二页主体', subjectType: 'ENTERPRISE', createdAt: '2026-09-13T11:00:00' },
      ],
      total: 11,
      pageNum: 2,
      pageSize: 10,
      totalPages: 2,
    })
    const next = wrapper.find('.el-pagination .btn-next')
    expect(next.exists()).toBe(true)
    await next.trigger('click')
    await flushPromises()
    expect(mockedQueue).toHaveBeenLastCalledWith(2, 10)
    expect(wrapper.text()).toContain('第二页主体')
    expect(wrapper.text()).not.toContain('第一页主体')
  })
})
