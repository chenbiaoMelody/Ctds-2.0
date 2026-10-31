import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import PortalOrderDetailView from './PortalOrderDetailView.vue'
import { getOrderDetail, type OrderDetail } from '../../api/order'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import { ORDER_NOT_FOUND_TIP } from '../../constants/order'

/**
 * 订单详情测试（WBS-3.7.3 hifi §4.4 / §6.2 T7~T8；C-7.1 S2-13 / S2-6 界面锚）：
 * - 快照渲染（产品/双方/定价/模板版本/发起时间）+ 状态 tag + 关联合约状态；
 * - 关联合约深链「前往合约工作台」（/contracts/workbench/{contractNo}）；
 * - 时间线动作标签（不含 DENIED_ACCESS——拒绝留痕不进用户读面）；
 * - 已关闭：关闭原因 + 关闭时间展示；
 * - 负向：非法订单号零请求；404/非本方同形 ORDER_NOT_FOUND_TIP。
 */
vi.mock('../../api/order', () => ({ getOrderDetail: vi.fn() }))

const mockedDetail = vi.mocked(getOrderDetail)

function detail(over: Partial<OrderDetail> = {}): OrderDetail {
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
    templateNo: 'CT000001',
    templateVersionNo: 1,
    closedReason: null,
    closedAt: null,
    actions: [
      {
        action: 'CREATE',
        fromStatus: null,
        toStatus: 'IN_PROGRESS',
        reason: null,
        operatorSubjectNo: 'S20260925000002',
        createdAt: '2026-11-01T10:00:00',
      },
    ],
    ...over,
  }
}

async function mountDetail(orderNo = 'OD000001') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/portal/orders', name: 'portal-order-list', component: { template: '<div />' } },
      {
        path: '/portal/orders/:orderNo',
        name: 'portal-order-detail',
        component: PortalOrderDetailView,
      },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push(`/portal/orders/${orderNo}`)
  await router.isReady()
  const wrapper = mount(PortalOrderDetailView, {
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
  mockedDetail.mockReset()
  setDemoSubject('S20260925000002')
  setActorMode('subject')
})

describe('订单详情（hifi §4.4）', () => {
  it('快照渲染：订单号/产品/双方/定价/模板版本/状态/合约状态', async () => {
    mockedDetail.mockResolvedValue(detail())
    const { wrapper } = await mountDetail()
    const text = wrapper.text()
    expect(text).toContain('OD000001')
    expect(text).toContain('W373 Demo API Feed')
    expect(text).toContain('S20260925000001')
    expect(text).toContain('S20260925000002')
    expect(text).toContain('按次（3.00）')
    expect(text).toContain('CT000001')
    expect(text).toContain('进行中')
    expect(mockedDetail).toHaveBeenCalledWith('OD000001')
  })

  it('关联合约深链「前往合约工作台」', async () => {
    mockedDetail.mockResolvedValue(detail())
    const { wrapper } = await mountDetail()
    const link = wrapper.find('.contract-workbench-link')
    expect(link.attributes('href')).toBe('/contracts/workbench/CO000021')
    expect(wrapper.text()).toContain('前往合约工作台')
  })

  it('时间线动作标签：创建订单（拒绝留痕 DENIED_ACCESS 不进用户读面）', async () => {
    mockedDetail.mockResolvedValue(detail({
      actions: [
        detail().actions[0],
        {
          action: 'DENIED_ACCESS',
          fromStatus: null,
          toStatus: null,
          reason: '非本方访问',
          operatorSubjectNo: 'S20260925000099',
          createdAt: '2026-11-01T11:00:00',
        },
      ],
    }))
    const { wrapper } = await mountDetail()
    expect(wrapper.text()).toContain('创建订单')
    expect(wrapper.text()).not.toContain('DENIED_ACCESS')
    expect(wrapper.text()).not.toContain('非本方访问')
  })

  it('已关闭：状态「已关闭」+ 关闭原因 + 关闭时间', async () => {
    mockedDetail.mockResolvedValue(detail({
      status: 'CLOSED',
      closedReason: '合约协商终止',
      closedAt: '2026-11-01T12:00:00',
      actions: [
        ...detail().actions,
        {
          action: 'CLOSE',
          fromStatus: 'IN_PROGRESS',
          toStatus: 'CLOSED',
          reason: '合约协商终止',
          operatorSubjectNo: 'S20260925000002',
          createdAt: '2026-11-01T12:00:00',
        },
      ],
    }))
    const { wrapper } = await mountDetail()
    const text = wrapper.text()
    expect(text).toContain('已关闭')
    expect(text).toContain('合约协商终止')
    expect(text).toContain('合约终止，订单关闭')
  })

  it('非法订单号：前端前置校验零请求 + 同形提示', async () => {
    const { wrapper } = await mountDetail('not-an-order')
    await flushPromises()
    expect(mockedDetail).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(ORDER_NOT_FOUND_TIP)
  })

  it('404/非本方：同形 ORDER_NOT_FOUND_TIP（1011C0002 文案一致）', async () => {
    mockedDetail.mockRejectedValue(new ApiError('1011C0002', ORDER_NOT_FOUND_TIP))
    const { wrapper } = await mountDetail('OD999999')
    await flushPromises()
    expect(wrapper.text()).toContain(ORDER_NOT_FOUND_TIP)
  })
})
