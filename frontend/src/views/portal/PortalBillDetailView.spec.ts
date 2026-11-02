import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import PortalBillDetailView from './PortalBillDetailView.vue'
import { getBillDetail, listReconciliations, type BillDetail, type Reconciliation } from '../../api/billing'
import { setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'

/**
 * 门户账单详情测试（WBS-3.7.7 hifi §3 P11；§9 T12；§8 账单核对 S1-3 对账结论区）：
 * 只读结构（零确认/更正/出账按钮）、快照与明细、合计对照、更正链、对账结论区、1011C0009 空态。
 */
vi.mock('../../api/billing', () => ({
  getBillDetail: vi.fn(),
  listReconciliations: vi.fn(),
}))

const mockedDetail = vi.mocked(getBillDetail)
const mockedReconciliations = vi.mocked(listReconciliations)

function detail(over: Partial<BillDetail> = {}): BillDetail {
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
    quantity: 10,
    unitPrice: '3.00',
    baseAmount: null,
    shareRate: null,
    amount: '30.00',
    status: 'CONFIRMED',
    correctionOfBillNo: null,
    createdAt: '2026-11-01T00:10:00',
    details: [{
      lineNo: 1,
      lineType: 'USAGE',
      description: '网关调用计量',
      meteringFlowId: 11,
      sourceType: 'GATEWAY_API',
      sourceNo: 'evt-1',
      occurredAt: '2026-10-01T09:00:00',
      quantity: 10,
      unitPrice: '3.00',
      amount: '30.00',
    }],
    actions: [{
      action: 'CREATE',
      fromStatus: null,
      toStatus: 'ISSUED',
      reason: '手动出账',
      operatorSubjectNo: 'S20260925000001',
      createdAt: '2026-11-01T00:10:00',
    }],
    correctionBillNos: [],
    ...over,
  }
}

function reconciliation(over: Partial<Reconciliation> = {}): Reconciliation {
  return {
    id: 1,
    contractNo: 'CO000001',
    period: '2026-10',
    counterUsedCount: 10,
    usageLogAllowedCount: 10,
    meteringFlowCount: 10,
    usageLogAllowedAllTime: 10,
    meteringFlowAllTime: 10,
    billQuantity: 10,
    billTotalAmount: '30.00',
    recomputedAmount: '30.00',
    conclusion: 'PASS',
    differences: [],
    createdBy: 'S20260925000001',
    createdAt: '2026-11-01T00:30:00',
    ...over,
  }
}

function page<T>(list: T[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 5, totalPages: 1 }
}

async function mountDetail(billNo = 'BL000001') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/portal/bills', name: 'portal-bills', component: { template: '<div />' } },
      { path: '/portal/bills/:billNo', name: 'portal-bill-detail', component: PortalBillDetailView },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push(`/portal/bills/${billNo}`)
  await router.isReady()
  const wrapper = mount(PortalBillDetailView, {
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
  mockedDetail.mockResolvedValue(detail())
  mockedReconciliations.mockResolvedValue(page([reconciliation()]))
})

describe('T12 门户账单详情只读（hifi §3 P11）', () => {
  it('渲染账单号 / 状态标签 / 快照 / 明细 / 合计对照', async () => {
    const { wrapper } = await mountDetail()
    const text = wrapper.text()
    expect(text).toContain('BL000001')
    expect(text).toContain('已确认')
    expect(text).toContain('CO000001')
    expect(text).toContain('evt-1')
    expect(wrapper.find('.detail-sum').text()).toContain('30.00')
    expect(wrapper.find('.bill-amount').text()).toContain('30.00')
  })

  it('只读结构：零确认 / 零更正 / 零出账按钮', async () => {
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.confirm-btn').exists()).toBe(false)
    expect(wrapper.find('.correct-btn').exists()).toBe(false)
    expect(wrapper.find('.outbill-btn').exists()).toBe(false)
    expect(wrapper.findAll('button').filter((btn) => btn.text().includes('确认')).length).toBe(0)
    expect(wrapper.findAll('button').filter((btn) => btn.text().includes('更正')).length).toBe(0)
  })

  it('合计不一致：提示以服务端为准（门户同构口径）', async () => {
    mockedDetail.mockResolvedValue(detail({ amount: '29.00' }))
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.total-mismatch').text()).toContain('账单金额以服务端为准')
  })

  it('更正链：原单可见更正单号，更正单可见返回原单', async () => {
    mockedDetail.mockResolvedValue(detail({ correctionBillNos: ['BL000003'] }))
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.correction-chain').text()).toContain('BL000003')

    mockedDetail.mockResolvedValue(detail({ billType: 'CORRECTION', correctionOfBillNo: 'BL000001' }))
    const second = await mountDetail()
    expect(second.wrapper.find('.correction-chain').text()).toContain('返回原单')
  })

  it('对账结论区（§8 账单核对 S1-3）：本方合约结论与差异明细只读可读', async () => {
    mockedReconciliations.mockResolvedValue(page([
      reconciliation(),
      reconciliation({
        id: 2,
        conclusion: 'DIFFERENCE',
        differences: [{
          type: 'AMOUNT_RECOMPUTE', leg: '账单金额复算', expected: '30.00', actual: '31.00', detail: '复算不一致',
        }],
      }),
    ]))
    const { wrapper } = await mountDetail()
    const section = wrapper.find('.reconcile-section').text()
    expect(section).toContain('对账结论')
    expect(section).toContain('通过')
    expect(section).toContain('差异')
    expect(section).toContain('账单金额复算')
    expect(section).toContain('复算不一致')
    expect(mockedReconciliations).toHaveBeenCalledWith('CO000001', expect.objectContaining({ pageNum: 1 }))
  })

  it('对账结论读取失败：不影响账单主体，仅该区提示', async () => {
    mockedReconciliations.mockRejectedValue(new Error('合约不存在或不可读'))
    const { wrapper } = await mountDetail()
    expect(wrapper.text()).toContain('BL000001')
    expect(wrapper.find('.reconcile-error').text()).toContain('合约不存在或不可读')
  })

  it('1011C0009：整页空态「账单不存在或不可读」（同码同文，不区分不存在与非本方）', async () => {
    mockedDetail.mockRejectedValue(Object.assign(new Error('账单或对账数据不存在或不可读'), { code: '1011C0009' }))
    const { wrapper } = await mountDetail('BL999999')
    expect(wrapper.find('.not-found').text()).toContain('账单不存在或不可读')
  })

  it('返回列表链接指向 /portal/bills', async () => {
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.back-btn').attributes('href')).toBe('/portal/bills')
  })
})
