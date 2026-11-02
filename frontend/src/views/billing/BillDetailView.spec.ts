import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import BillDetailView from './BillDetailView.vue'
import { confirmBill, createCorrection, getBillDetail, type BillDetail } from '../../api/billing'
import { setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'

/**
 * 运营账单详情测试（WBS-3.7.7 hifi §3 P3/P4/P5；§9 T3/T4/T5/T13）：
 * - T3 五分区：快照（按档显示数量·单价 / 基数·费率）/ 明细逐行 / 合计对照（相等·不等两态）/ 留痕 / 更正链；
 * - T4 确认动作与状态门槛：ISSUED 可点 → 成功刷新 CONFIRMED；FROZEN 禁用 + 提示；409 C0011 原样展示并刷新；
 * - T5 更正弹窗：空值拦截零请求 / 负金额字符串提交 / 成功跳更正单 / 409 原样展示；
 * - T13 XSS 渲染锚：更正原因注入 HTML → 按纯文本显示，DOM 无 img，脚本未执行。
 */
vi.mock('../../api/billing', () => ({
  getBillDetail: vi.fn(),
  confirmBill: vi.fn(),
  createCorrection: vi.fn(),
}))

const mockedDetail = vi.mocked(getBillDetail)
const mockedConfirm = vi.mocked(confirmBill)
const mockedCorrection = vi.mocked(createCorrection)

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
    status: 'ISSUED',
    correctionOfBillNo: null,
    createdAt: '2026-11-01T00:10:00',
    details: [
      {
        lineNo: 1,
        lineType: 'USAGE',
        description: '网关调用计量',
        meteringFlowId: 11,
        sourceType: 'GATEWAY_API',
        sourceNo: 'evt-1',
        occurredAt: '2026-10-01T09:00:00',
        quantity: 4,
        unitPrice: '3.00',
        amount: '12.00',
      },
      {
        lineNo: 2,
        lineType: 'USAGE',
        description: '网关调用计量',
        meteringFlowId: 12,
        sourceType: 'GATEWAY_API',
        sourceNo: 'evt-2',
        occurredAt: '2026-10-02T09:00:00',
        quantity: 6,
        unitPrice: '3.00',
        amount: '18.00',
      },
    ],
    actions: [
      {
        action: 'CREATE',
        fromStatus: null,
        toStatus: 'ISSUED',
        reason: '手动出账',
        operatorSubjectNo: 'S20260925000001',
        createdAt: '2026-11-01T00:10:00',
      },
    ],
    correctionBillNos: [],
    ...over,
  }
}

async function mountDetail(billNo = 'BL000001') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/billing', name: 'billing', component: { template: '<div />' } },
      { path: '/billing/bills/:billNo', name: 'billing-detail', component: BillDetailView },
    ],
  })
  await router.push(`/billing/bills/${billNo}`)
  await router.isReady()
  const wrapper = mount(BillDetailView, {
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
  delete (window as unknown as Record<string, unknown>).__xss
  setDemoSubject('S20260925000001')
  setActorMode('operator')
  mockedDetail.mockResolvedValue(detail())
})

describe('T3 账单详情五分区（hifi §3 P3）', () => {
  it('快照区：按次档显示数量与单价（基数/费率为空不渲染）', async () => {
    const { wrapper } = await mountDetail()
    const snapshot = wrapper.find('.snapshot').text()
    expect(snapshot).toContain('CO000001')
    expect(snapshot).toContain('2026-10')
    expect(snapshot).toContain('S20260925000002')
    expect(snapshot).toContain('气象数据 API')
    expect(snapshot).toContain('按次')
    expect(snapshot).toContain('30.00')
    expect(snapshot).not.toContain('约定交易额基数')
    expect(snapshot).not.toContain('分成费率')
  })

  it('快照区：分成档显示基数与费率双快照', async () => {
    mockedDetail.mockResolvedValue(detail({
      pricingModel: 'REVENUE_SHARE',
      quantity: null,
      unitPrice: null,
      baseAmount: '10000.00',
      shareRate: '5.00',
    }))
    const { wrapper } = await mountDetail()
    const snapshot = wrapper.find('.snapshot').text()
    expect(snapshot).toContain('约定交易额基数')
    expect(snapshot).toContain('10000.00')
    expect(snapshot).toContain('分成费率')
    expect(snapshot).toContain('5.00')
  })

  it('明细区：逐行渲染（源单号 / 事件时点 / 数量 / 单价 / 金额）', async () => {
    const { wrapper } = await mountDetail()
    const text = wrapper.find('.detail-table').text()
    expect(text).toContain('evt-1')
    expect(text).toContain('evt-2')
    expect(text).toContain('12.00')
    expect(text).toContain('18.00')
    expect(text).toContain('网关 API 调用')
  })

  it('合计对照相等：不出现「以服务端为准」提示', async () => {
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.detail-sum').text()).toContain('30.00')
    expect(wrapper.find('.total-mismatch').exists()).toBe(false)
  })

  it('合计对照不等：并列提示「账单金额以服务端为准」，不改值不拦截', async () => {
    mockedDetail.mockResolvedValue(detail({
      amount: '31.00',
      details: [
        {
          lineNo: 1, lineType: 'USAGE', description: 'x', meteringFlowId: 1,
          sourceType: 'GATEWAY_API', sourceNo: 'evt-1', occurredAt: '2026-10-01T09:00:00',
          quantity: 1, unitPrice: '3.00', amount: '12.00',
        },
        {
          lineNo: 2, lineType: 'USAGE', description: 'y', meteringFlowId: 2,
          sourceType: 'GATEWAY_API', sourceNo: 'evt-2', occurredAt: '2026-10-02T09:00:00',
          quantity: 1, unitPrice: '3.00', amount: '18.00',
        },
      ],
    }))
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.detail-sum').text()).toContain('30.00')
    expect(wrapper.find('.bill-amount').text()).toContain('31.00')
    expect(wrapper.find('.total-mismatch').text()).toContain('账单金额以服务端为准')
  })

  it('留痕区：动作中文 + 原状态→新状态 + 操作人', async () => {
    const { wrapper } = await mountDetail()
    const text = wrapper.find('.action-log-table').text()
    expect(text).toContain('创建')
    expect(text).toContain('已开具')
    expect(text).toContain('S20260925000001')
  })

  it('更正链：原单可见更正单链接，更正单可见返回原单', async () => {
    mockedDetail.mockResolvedValue(detail({ correctionBillNos: ['BL000002'] }))
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.correction-chain').text()).toContain('BL000002')

    mockedDetail.mockResolvedValue(detail({ billType: 'CORRECTION', correctionOfBillNo: 'BL000001' }))
    const second = await mountDetail()
    expect(second.wrapper.find('.correction-chain').text()).toContain('返回原单')
  })

  it('1011C0009：整页空态「账单不存在或不可读」（同码同文）', async () => {
    mockedDetail.mockRejectedValue(Object.assign(new Error('账单或对账数据不存在或不可读'), { code: '1011C0009' }))
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.not-found').text()).toContain('账单不存在或不可读')
  })

  it('其余错误：el-alert 原样展示 + 重试', async () => {
    mockedDetail.mockRejectedValueOnce(new Error('请求失败，请稍后重试'))
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.detail-error').text()).toContain('请求失败，请稍后重试')
    await wrapper.find('.retry-btn').trigger('click')
    await flushPromises()
    expect(mockedDetail).toHaveBeenCalledTimes(2)
  })

  it('生成人取创建留痕操作人（无留痕时显示占位）', async () => {
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.snapshot').text()).toContain('生成人')
    mockedDetail.mockResolvedValue(detail({ actions: [] }))
    const second = await mountDetail()
    expect(second.wrapper.find('.snapshot').text()).toContain('生成人')
  })
})

describe('T4 确认动作与状态门槛（hifi §3 P4 / §6）', () => {
  it('ISSUED：确认按钮可用，成功后刷新为已确认', async () => {
    mockedConfirm.mockResolvedValue(detail({ status: 'CONFIRMED' }))
    mockedDetail.mockResolvedValueOnce(detail())
    mockedDetail.mockResolvedValueOnce(detail({
      status: 'CONFIRMED',
      actions: [
        ...detail().actions,
        {
          action: 'CONFIRM', fromStatus: 'ISSUED', toStatus: 'CONFIRMED',
          reason: '运营确认账单', operatorSubjectNo: 'S20260925000001',
          createdAt: '2026-11-01T00:20:00',
        },
      ],
    }))
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.confirm-btn').attributes('disabled')).toBeUndefined()
    await wrapper.find('.confirm-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.confirm-ok').trigger('click')
    await flushPromises()
    expect(mockedConfirm).toHaveBeenCalledWith('BL000001')
    expect(mockedDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.bill-status').text()).toContain('已确认')
  })

  it('FROZEN：确认禁用 + 冻结提示', async () => {
    mockedDetail.mockResolvedValue(detail({ status: 'FROZEN' }))
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.frozen-tip').text()).toContain('账单已冻结，需对账澄清后重新对账解冻')
    expect(wrapper.find('.confirm-btn').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.correct-btn').attributes('disabled')).toBeDefined()
  })

  it('CONFIRMED：确认禁用（已确认），更正仍可用', async () => {
    mockedDetail.mockResolvedValue(detail({ status: 'CONFIRMED' }))
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.confirm-btn').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.correct-btn').attributes('disabled')).toBeUndefined()
  })

  it('服务端 409 C0011：原样展示并自动刷新详情', async () => {
    mockedConfirm.mockRejectedValue(new Error('账单已冻结，需对账澄清后重新对账解冻'))
    const { wrapper } = await mountDetail()
    await wrapper.find('.confirm-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.confirm-ok').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('账单已冻结，需对账澄清后重新对账解冻')
    expect(mockedDetail).toHaveBeenCalledTimes(2)
  })
})

describe('T5 更正弹窗（hifi §3 P5）', () => {
  async function openCorrection(wrapper: ReturnType<typeof mount>) {
    await wrapper.find('.correct-btn').trigger('click')
    await flushPromises()
  }

  it('金额留空：前端拦截（零请求）', async () => {
    const { wrapper } = await mountDetail()
    await openCorrection(wrapper)
    await wrapper.find('.correction-reason textarea').setValue('红字冲正')
    await wrapper.find('.correction-submit').trigger('click')
    await flushPromises()
    expect(mockedCorrection).not.toHaveBeenCalled()
    expect(wrapper.find('.correction-error').text()).toContain('请填写更正金额')
  })

  it('原因留空：前端拦截（零请求）', async () => {
    const { wrapper } = await mountDetail()
    await openCorrection(wrapper)
    await wrapper.find('.correction-amount input').setValue('-3.00')
    await wrapper.find('.correction-submit').trigger('click')
    await flushPromises()
    expect(mockedCorrection).not.toHaveBeenCalled()
    expect(wrapper.find('.correction-error').text()).toContain('请填写更正原因')
  })

  it('负金额：请求体 amount = "-3.00"（字符串），成功跳更正单详情', async () => {
    mockedCorrection.mockResolvedValue(detail({ billNo: 'BL000002', billType: 'CORRECTION', correctionOfBillNo: 'BL000001' }))
    const { wrapper, router } = await mountDetail()
    await openCorrection(wrapper)
    await wrapper.find('.correction-amount input').setValue('-3.00')
    await wrapper.find('.correction-reason textarea').setValue('红字冲正')
    await wrapper.find('.correction-submit').trigger('click')
    await flushPromises()
    expect(mockedCorrection).toHaveBeenCalledWith('BL000001', { amount: '-3.00', reason: '红字冲正' })
    expect(router.currentRoute.value.path).toBe('/billing/bills/BL000002')
  })

  it('409 冻结：弹窗内原样展示服务端文案，弹窗不关闭', async () => {
    mockedCorrection.mockRejectedValue(new Error('账单已冻结，需对账澄清后重新对账解冻'))
    const { wrapper } = await mountDetail()
    await openCorrection(wrapper)
    await wrapper.find('.correction-amount input').setValue('3.00')
    await wrapper.find('.correction-reason textarea').setValue('补差')
    await wrapper.find('.correction-submit').trigger('click')
    await flushPromises()
    expect(wrapper.find('.correction-error').text()).toContain('账单已冻结')
    expect(wrapper.find('.correction-submit').exists()).toBe(true)
  })

  it('更正语义提示：不改原单，另出更正单', async () => {
    const { wrapper } = await mountDetail()
    await openCorrection(wrapper)
    expect(document.body.textContent).toContain('更正不是修改原单')
  })

  it('原因长度上限 200 字并显示计数', async () => {
    const { wrapper } = await mountDetail()
    await openCorrection(wrapper)
    expect(wrapper.find('.correction-reason textarea').attributes('maxlength')).toBe('200')
    await wrapper.find('.correction-reason textarea').setValue('红字冲正')
    expect(wrapper.text()).toContain('4/200')
  })
})

describe('T13 XSS 渲染锚（DB-103 ②）', () => {
  it('留痕原因注入 HTML：按纯文本渲染，DOM 无 img，脚本未执行', async () => {
    const payload = '<img src=x onerror="window.__xss=1">'
    mockedDetail.mockResolvedValue(detail({
      actions: [{
        action: 'CREATE', fromStatus: null, toStatus: 'ISSUED', reason: payload,
        operatorSubjectNo: 'S20260925000001', createdAt: '2026-11-01T00:10:00',
      }],
    }))
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.action-log-table').text()).toContain(payload)
    expect(wrapper.html()).not.toContain('<img')
    expect((window as unknown as Record<string, unknown>).__xss).toBeUndefined()
  })

  it('明细说明注入 HTML：同样按文本渲染', async () => {
    const payload = '<script>window.__xss=1</script>'
    mockedDetail.mockResolvedValue(detail({
      details: [{
        lineNo: 1, lineType: 'USAGE', description: payload, meteringFlowId: 1,
        sourceType: 'GATEWAY_API', sourceNo: 'evt-1', occurredAt: '2026-10-01T09:00:00',
        quantity: 1, unitPrice: '3.00', amount: '3.00',
      }],
    }))
    const { wrapper } = await mountDetail()
    expect(wrapper.find('.detail-table').text()).toContain(payload)
    expect(wrapper.html()).not.toContain('<script>')
    expect((window as unknown as Record<string, unknown>).__xss).toBeUndefined()
  })
})
