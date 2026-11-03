import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import PortalBillListView from './PortalBillListView.vue'
import { listMyBills, type BillSummary } from '../../api/billing'
import { setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'

/**
 * 门户「我的账单」列表测试（WBS-3.7.7 hifi §3 P10；§9 T11）：
 * 本方账单渲染（含我的角色中文）/ 三过滤 / 分页 / 空态 / 失败文案原样 / 详情深链。
 */
vi.mock('../../api/billing', () => ({ listMyBills: vi.fn() }))

const mockedBills = vi.mocked(listMyBills)

function bill(over: Partial<BillSummary> = {}): BillSummary {
  return {
    billNo: 'BL000001',
    billType: 'NORMAL',
    contractNo: 'CO000001',
    requesterNo: 'S20260925000002',
    providerNo: 'S20260925000001',
    productId: 15,
    productName: '气象数据 API',
    period: '2026-10',
    pricingModel: 'PER_CALL',
    amount: '30.00',
    status: 'CONFIRMED',
    correctionOfBillNo: null,
    createdAt: '2026-11-01T00:10:00',
    myRole: 'REQUESTER',
    ...over,
  }
}

function page<T>(list: T[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountList() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/portal/bills', name: 'portal-bills', component: PortalBillListView },
      { path: '/portal/bills/:billNo', name: 'portal-bill-detail', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push('/portal/bills')
  await router.isReady()
  const wrapper = mount(PortalBillListView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.clearAllMocks()
  setDemoSubject('S20260925000002')
  setActorMode('subject')
  mockedBills.mockResolvedValue(page([bill()]))
})

describe('T11 门户我的账单（hifi §3 P10）', () => {
  it('挂载即拉取第 1 页并渲染账单号/类型/产品/账期/我的角色/金额/状态', async () => {
    const { wrapper } = await mountList()
    expect(mockedBills).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 10 }))
    const text = wrapper.text()
    expect(text).toContain('BL000001')
    expect(text).toContain('普通账单')
    expect(text).toContain('气象数据 API')
    expect(text).toContain('2026-10')
    expect(text).toContain('需方')
    expect(text).toContain('30.00')
    expect(text).toContain('已确认')
  })

  it('供方视角：我的角色标注「供方」', async () => {
    mockedBills.mockResolvedValue(page([bill({ myRole: 'PROVIDER' })]))
    const { wrapper } = await mountList()
    expect(wrapper.find('.bill-table').text()).toContain('供方')
  })

  it('页面说明：仅显示与本主体相关的账单', async () => {
    const { wrapper } = await mountList()
    expect(wrapper.text()).toContain('仅显示与本主体相关的账单（我是需方或供方）')
  })

  it('合约号 / 产品 ID 过滤透传 + 重置回第 1 页', async () => {
    const { wrapper } = await mountList()
    await wrapper.find('.filter-contract input').setValue('CO000001')
    await wrapper.find('.filter-product input').setValue('15')
    await wrapper.find('.bill-search-btn').trigger('click')
    await flushPromises()
    expect(mockedBills).toHaveBeenLastCalledWith(expect.objectContaining({
      contractNo: 'CO000001', productId: 15, pageNum: 1,
    }))

    await wrapper.find('.bill-reset-btn').trigger('click')
    await flushPromises()
    expect(mockedBills).toHaveBeenLastCalledWith(expect.objectContaining({
      contractNo: undefined, productId: undefined, pageNum: 1,
    }))
  })

  it('分页：切换页码以新页码请求', async () => {
    mockedBills.mockResolvedValue({ list: [bill()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const { wrapper } = await mountList()
    await wrapper.find('.list-pagination .btn-next').trigger('click')
    await flushPromises()
    expect(mockedBills).toHaveBeenLastCalledWith(expect.objectContaining({ pageNum: 2 }))
  })

  it('分页档位 10/20/50：切换为 20 后回第 1 页重新请求', async () => {
    mockedBills.mockResolvedValue({ list: [bill()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const { wrapper } = await mountList()
    const pagination = wrapper.findComponent({ name: 'ElPagination' })
    expect(pagination.props('pageSizes')).toEqual([10, 20, 50])
    pagination.vm.$emit('size-change', 20)
    await flushPromises()
    expect(mockedBills).toHaveBeenLastCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 20 }))
  })

  it('空态「暂无账单」', async () => {
    mockedBills.mockResolvedValue(page([]))
    const { wrapper } = await mountList()
    expect(wrapper.text()).toContain('暂无账单')
  })

  it('失败态：服务端文案原样展示（1011C0002 认证失败文案不二次包装）', async () => {
    mockedBills.mockRejectedValue(Object.assign(new Error('认证失败或身份已失效'), { code: '1000C0002' }))
    await mountList()
    await flushPromises()
    expect(document.body.textContent).toContain('认证失败或身份已失效')
  })

  it('账单号链接进入门户账单详情深链', async () => {
    const { wrapper, router } = await mountList()
    await wrapper.find('.bill-link').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/portal/bills/BL000001')
  })
})
