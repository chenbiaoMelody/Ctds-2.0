import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  confirmBill,
  createCorrection,
  getBillDetail,
  listGovernanceBills,
  listGovernanceMeteringFlows,
  listMyBills,
  listReconciliations,
  outbillBill,
  runReconciliation,
} from './billing'
import { listGovernanceOrders } from './order'
import {
  BILL_ACTION_LABELS,
  BILL_STATUS_LABELS,
  BILL_STATUS_TAG,
  BILL_TYPE_LABELS,
  CONCLUSION_LABELS,
  CONCLUSION_TAG,
  DIFFERENCE_TYPE_LABELS,
  SOURCE_TYPE_LABELS,
} from '../constants/billing'
import { setDemoSubject } from './client'
import { setActorMode } from '../stores/demoIdentity'

/**
 * 计费域 API 形态测试（WBS-3.7.7 hifi §1 契约表 / §2.1 函数表 / §2.3 常量）：
 * - 8 个既有端点（B-R1~B-R4 / B-W1~B-W4）+ 1 个新增只读端点（R-M4）；
 * - 角色头走 `billingRolesHeader()`：运营档 admin / 普通档 requester,provider（§5）；
 * - 金额一律字符串提交（§2.1 更正金额注释）；
 * - 标签常量单一源：未知枚举值不隐藏（§7 口径）。
 */

interface CapturedRequest {
  url: string
  method: string
  headers: Record<string, string>
  body?: string
}

function stubFetchCapture(): CapturedRequest[] {
  const calls: CapturedRequest[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const raw = (init?.headers ?? {}) as Record<string, string>
    const headers: Record<string, string> = {}
    if (typeof Headers !== 'undefined' && raw instanceof Headers) {
      raw.forEach((value, key) => { headers[key] = value })
    } else {
      Object.assign(headers, raw)
    }
    calls.push({
      url: String(input),
      method: init?.method ?? 'GET',
      headers,
      body: typeof init?.body === 'string' ? init.body : undefined,
    })
    return new Response(JSON.stringify({ code: '0', message: 'success', data: {} }),
      { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
  return calls
}

beforeEach(() => {
  localStorage.clear()
  setDemoSubject('S20260925000001')
  setActorMode('operator')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('计费域 API 形态（hifi §1 / §2.1）', () => {
  it('listGovernanceBills = GET /api/v1/billing/bills/governance，七维过滤透传', async () => {
    const calls = stubFetchCapture()
    await listGovernanceBills({
      pageNum: 2,
      pageSize: 20,
      contractNo: 'CO000001',
      period: '2026-10',
      productId: 15,
      requesterNo: 'S20260925000002',
      providerNo: 'S20260925000001',
      status: 'ISSUED',
      billType: 'NORMAL',
    })
    expect(calls).toHaveLength(1)
    expect(calls[0].method).toBe('GET')
    expect(calls[0].url).toBe('/api/v1/billing/bills/governance?pageNum=2&pageSize=20'
      + '&contractNo=CO000001&period=2026-10&productId=15'
      + '&requesterNo=S20260925000002&providerNo=S20260925000001&status=ISSUED&billType=NORMAL')
    expect(calls[0].headers['X-Ctds-Roles']).toBe('admin')
  })

  it('listGovernanceBills 未填过滤项：查询串只含分页参数', async () => {
    const calls = stubFetchCapture()
    await listGovernanceBills({ pageNum: 1, pageSize: 10 })
    expect(calls[0].url).toBe('/api/v1/billing/bills/governance?pageNum=1&pageSize=10')
  })

  it('getBillDetail = GET /api/v1/billing/bills/{billNo}', async () => {
    const calls = stubFetchCapture()
    await getBillDetail('BL000001')
    expect(calls[0].method).toBe('GET')
    expect(calls[0].url).toBe('/api/v1/billing/bills/BL000001')
  })

  it('outbillBill = POST /api/v1/billing/bills，分成基数以字符串提交', async () => {
    const calls = stubFetchCapture()
    await outbillBill({ contractNo: 'CO000001', period: '2026-10', agreedTransactionAmount: '10000.00' })
    expect(calls[0].method).toBe('POST')
    expect(calls[0].url).toBe('/api/v1/billing/bills')
    const sent = JSON.parse(calls[0].body ?? '{}')
    expect(sent.contractNo).toBe('CO000001')
    expect(sent.period).toBe('2026-10')
    expect(sent.agreedTransactionAmount).toBe('10000.00')
  })

  it('outbillBill 未填基数：请求体省略该字段（服务端按档位兜底）', async () => {
    const calls = stubFetchCapture()
    await outbillBill({ contractNo: 'CO000002' })
    const sent = JSON.parse(calls[0].body ?? '{}')
    expect(sent).toEqual({ contractNo: 'CO000002' })
  })

  it('confirmBill = POST /api/v1/billing/bills/{billNo}/confirm（无请求体）', async () => {
    const calls = stubFetchCapture()
    await confirmBill('BL000002')
    expect(calls[0].method).toBe('POST')
    expect(calls[0].url).toBe('/api/v1/billing/bills/BL000002/confirm')
    expect(calls[0].body).toBeUndefined()
  })

  it('createCorrection = POST /api/v1/billing/bills/{billNo}/corrections，负金额原样字符串提交', async () => {
    const calls = stubFetchCapture()
    await createCorrection('BL000003', { amount: '-3.00', reason: '红字冲正' })
    expect(calls[0].method).toBe('POST')
    expect(calls[0].url).toBe('/api/v1/billing/bills/BL000003/corrections')
    const sent = JSON.parse(calls[0].body ?? '{}')
    expect(sent.amount).toBe('-3.00')
    expect(typeof sent.amount).toBe('string')
    expect(sent.reason).toBe('红字冲正')
  })

  it('listMyBills = GET /api/v1/billing/bills/mine（普通档角色头 requester,provider）', async () => {
    setActorMode('subject')
    const calls = stubFetchCapture()
    await listMyBills({ pageNum: 1, pageSize: 10, contractNo: 'CO000001', period: '2026-10', productId: 15 })
    expect(calls[0].url).toBe('/api/v1/billing/bills/mine?pageNum=1&pageSize=10'
      + '&contractNo=CO000001&period=2026-10&productId=15')
    expect(calls[0].headers['X-Ctds-Roles']).toBe('requester,provider')
  })

  it('listReconciliations = GET /api/v1/billing/contracts/{contractNo}/reconciliations', async () => {
    const calls = stubFetchCapture()
    await listReconciliations('CO000001', { pageNum: 1, pageSize: 5, period: '2026-10' })
    expect(calls[0].url).toBe('/api/v1/billing/contracts/CO000001/reconciliations?pageNum=1&pageSize=5&period=2026-10')
  })

  it('runReconciliation = POST .../reconciliations?period=...（period 必填走查询串）', async () => {
    const calls = stubFetchCapture()
    await runReconciliation('CO000001', '2026-10')
    expect(calls[0].method).toBe('POST')
    expect(calls[0].url).toBe('/api/v1/billing/contracts/CO000001/reconciliations?period=2026-10')
  })

  it('listGovernanceMeteringFlows = GET /api/v1/metering/flows/governance（新增 R-M4）', async () => {
    const calls = stubFetchCapture()
    await listGovernanceMeteringFlows({
      pageNum: 1,
      pageSize: 10,
      contractNo: 'CO000001',
      requesterNo: 'S20260925000002',
      sourceType: 'GATEWAY_API',
      from: '2026-10-01 00:00:00',
      to: '2026-10-31 23:59:59',
    })
    expect(calls[0].method).toBe('GET')
    expect(calls[0].url).toBe('/api/v1/metering/flows/governance?pageNum=1&pageSize=10'
      + '&contractNo=CO000001&requesterNo=S20260925000002&sourceType=GATEWAY_API'
      + '&from=2026-10-01+00%3A00%3A00&to=2026-10-31+23%3A59%3A59')
    expect(calls[0].headers['X-Ctds-Roles']).toBe('admin')
  })

  it('listGovernanceOrders = GET /api/v1/orders/governance（复用 R-OR3，订单端点收口 api/order.ts）', async () => {
    const calls = stubFetchCapture()
    await listGovernanceOrders({
      pageNum: 1,
      pageSize: 10,
      status: 'COMPLETED',
      productId: 15,
      contractNo: 'CO000001',
      requesterNo: 'S20260925000002',
    })
    expect(calls[0].url).toBe('/api/v1/orders/governance?pageNum=1&pageSize=10'
      + '&status=COMPLETED&productId=15&contractNo=CO000001&requesterNo=S20260925000002')
    expect(calls[0].headers['X-Ctds-Roles']).toBe('admin')
  })
})

describe('计费域错误透传（§7 文案口径）', () => {
  it('1011C0010 重复出账经 ApiError 原样抛出（不二次包装）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ code: '1011C0010', message: '该合约该周期账单已存在，请勿重复出账', data: null }),
      { status: 409, headers: { 'Content-Type': 'application/json' } })))
    await expect(outbillBill({ contractNo: 'CO000001' }))
      .rejects.toMatchObject({ code: '1011C0010', message: '该合约该周期账单已存在，请勿重复出账' })
  })

  it('1011C0013 缺分成基数经 ApiError 原样抛出', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ code: '1011C0013', message: '交易额分成档须提供约定交易额', data: null }),
      { status: 400, headers: { 'Content-Type': 'application/json' } })))
    await expect(outbillBill({ contractNo: 'CO000001' }))
      .rejects.toMatchObject({ code: '1011C0013', message: '交易额分成档须提供约定交易额' })
  })
})

describe('计费域标签常量（hifi §2.3 单一源）', () => {
  it('账单状态三态标签与 tag 类型', () => {
    expect(BILL_STATUS_LABELS).toEqual({ ISSUED: '已开具', CONFIRMED: '已确认', FROZEN: '已冻结' })
    expect(BILL_STATUS_TAG).toEqual({ ISSUED: 'info', CONFIRMED: 'success', FROZEN: 'danger' })
  })

  it('账单类型与结论标签', () => {
    expect(BILL_TYPE_LABELS).toEqual({ NORMAL: '普通账单', CORRECTION: '更正单' })
    expect(CONCLUSION_LABELS).toEqual({ PASS: '通过', DIFFERENCE: '差异' })
    expect(CONCLUSION_TAG).toEqual({ PASS: 'success', DIFFERENCE: 'danger' })
  })

  it('留痕动作与源类型标签（四源）', () => {
    expect(BILL_ACTION_LABELS).toEqual({
      CREATE: '创建', CONFIRM: '确认', FREEZE: '冻结', UNFREEZE: '解冻',
    })
    expect(SOURCE_TYPE_LABELS).toEqual({
      GATEWAY_API: '网关 API 调用',
      FILE_DELIVERY: '文件交付',
      SANDBOX_EXPORT: '沙箱导出',
      SANDBOX_JOB: '沙箱任务',
    })
  })

  it('差异类型七类齐备（未知值由视图原样显示，不在此处兜底）', () => {
    expect(Object.keys(DIFFERENCE_TYPE_LABELS).sort()).toEqual([
      'AMOUNT_RECOMPUTE',
      'BILL_QUANTITY_VS_METERING',
      'COUNTER_VS_USAGE_LOG_ALL',
      'EXTRA_IN_BILL',
      'MISSING_IN_BILL',
      'USAGE_LOG_VS_METERING_ALL',
      'USAGE_LOG_VS_METERING_PERIOD',
    ])
  })
})
