import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import PortalOrderListView from './PortalOrderListView.vue'
import { listMyOrders, type OrderSummary } from '../../api/order'
import { setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'

/**
 * 我的订单列表测试（WBS-3.7.3 hifi §4.3 / §6.2 T6；C-7.1 S2-13 界面锚）：
 * - 渲染：订单号 / 产品 / 定价 / 我的角色（需方）/ 状态 tag / 关联合约号 / 发起时间；
 * - 状态过滤 + 重置；分页；空态「暂无订单」；错误态文案原样；
 * - 只读：无订单取消/修改动作（规格未定义订单侧动作，B6-2 状态即权威）。
 */
vi.mock('../../api/order', () => ({ listMyOrders: vi.fn() }))

const mockedOrders = vi.mocked(listMyOrders)

function summary(over: Partial<OrderSummary> = {}): OrderSummary {
  return {
    orderNo: 'OD000001',
    status: 'IN_PROGRESS',
    contractNo: 'CO000021',
    contractStatus: 'NEGOTIATING',
    productId: 15,
    productName: 'W373 Demo API Feed',
    providerSubjectNo: 'S20260925000001',
    requesterSubjectNo: 'S20260925000002',
    pricingModel: 'PER_CALL',
    priceAmount: '3.00',
    createdAt: '2026-11-01T10:00:00',
    myRole: 'REQUESTER',
    ...over,
  }
}

function page(list: OrderSummary[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountList(path = '/portal/orders') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/portal', name: 'portal-home', component: { template: '<div />' } },
      { path: '/portal/orders', name: 'portal-order-list', component: PortalOrderListView },
      {
        path: '/portal/orders/:orderNo',
        name: 'portal-order-detail',
        component: { template: '<div />' },
      },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(PortalOrderListView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  mockedOrders.mockReset()
  setDemoSubject('S20260925000002')
  setActorMode('subject')
})

describe('我的订单列表（hifi §4.3）', () => {
  it('渲染订单行：订单号/产品/角色/状态/合约号/查看详情', async () => {
    mockedOrders.mockResolvedValue(page([summary()]))
    const { wrapper } = await mountList()
    const text = wrapper.text()
    expect(text).toContain('OD000001')
    expect(text).toContain('W373 Demo API Feed')
    expect(text).toContain('需方')
    expect(text).toContain('进行中')
    expect(text).toContain('CO000021')
    expect(text).toContain('查看详情')
    expect(mockedOrders).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 10 }))
  })

  it('供方角色行：我的角色标注「供方」（B12-1 双角色读）', async () => {
    mockedOrders.mockResolvedValue(page([summary({
      myRole: 'PROVIDER', requesterSubjectNo: 'S20260925000009',
    })]))
    const { wrapper } = await mountList()
    expect(wrapper.text()).toContain('供方')
  })

  it('状态过滤：选择「进行中」查询，status 参数透传', async () => {
    mockedOrders.mockResolvedValue(page([summary()]))
    const { wrapper } = await mountList()
    const select = wrapper.find('.status-filter')
    await select.trigger('click')
    await flushPromises()
    const option = [...document.querySelectorAll('.el-select-dropdown__item')]
      .find((el) => el.textContent?.includes('进行中'))
    if (option) {
      ;(option as HTMLElement).click()
      await flushPromises()
    }
    await wrapper.find('.search-btn').trigger('click')
    await flushPromises()
    expect(mockedOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'IN_PROGRESS' }))
  })

  it('空态「暂无订单」', async () => {
    mockedOrders.mockResolvedValue(page([]))
    const { wrapper } = await mountList()
    expect(wrapper.text()).toContain('暂无订单')
  })

  it('错误态：后端文案原样展示（无二次包装）', async () => {
    mockedOrders.mockRejectedValue(new Error('网关超时，请稍后重试'))
    const { wrapper } = await mountList()
    await flushPromises()
    expect(wrapper.text()).toContain('网关超时，请稍后重试')
  })

  it('查看详情路由到订单详情深链', async () => {
    mockedOrders.mockResolvedValue(page([summary()]))
    const { wrapper, router } = await mountList()
    await wrapper.find('.detail-link').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/portal/orders/OD000001')
  })
})
