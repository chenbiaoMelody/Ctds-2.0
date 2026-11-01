import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createOrder, listMyOrders, getOrderDetail } from './order'
import { ORDER_STATUS_LABELS, ORDER_ACTION_LABELS, ORDER_NOT_FOUND_TIP } from '../constants/order'
import { setDemoSubject } from './client'
import { setActorMode } from '../stores/demoIdentity'

/**
 * 订单域 API 形态测试（WBS-3.7.3 hifi §1 契约表 / §4.1 / §6.2 T1）：
 * - W-OR1 `POST /api/v1/orders`（角色头 provider，body 含 requestId 与条款值）；
 * - R-OR1 `GET /api/v1/orders/mine`（分页 + 过滤参数）；
 * - R-OR2 `GET /api/v1/orders/{orderNo}`（详情）；
 * - 常量三态 / 动作标签单一源（hifi §4.6）；
 * - 后端 1011C0001/1011C0003 错误经 ApiError 原样透传（文案服务端单点）。
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
  setActorMode('subject')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('订单域 API 形态（hifi §1）', () => {
  it('createOrder = POST /api/v1/orders，角色头 provider，body 含 requestId', async () => {
    const calls = stubFetchCapture()
    await createOrder({
      productId: 15,
      templateNo: 'CT000001',
      templateVersionNo: 1,
      requestId: 'req-abc-123',
      clauseValues: { slots: { subject_matter: 'x' }, strategy: {} },
    })
    expect(calls).toHaveLength(1)
    expect(calls[0].method).toBe('POST')
    expect(calls[0].url).toBe('/api/v1/orders')
    expect(calls[0].headers['X-Ctds-Roles']).toBe('provider')
    expect(calls[0].headers['X-Ctds-Subject']).toBe('S20260925000001')
    const sent = JSON.parse(calls[0].body ?? '{}')
    expect(sent.requestId).toBe('req-abc-123')
    expect(sent.productId).toBe(15)
    expect(sent.templateNo).toBe('CT000001')
  })

  it('listMyOrders = GET /api/v1/orders/mine，分页与状态过滤参数透传', async () => {
    const calls = stubFetchCapture()
    await listMyOrders({ pageNum: 2, pageSize: 10, status: 'IN_PROGRESS' })
    expect(calls).toHaveLength(1)
    expect(calls[0].method).toBe('GET')
    expect(calls[0].url).toBe('/api/v1/orders/mine?pageNum=2&pageSize=10&status=IN_PROGRESS')
    expect(calls[0].headers['X-Ctds-Roles']).toBe('provider')
  })

  it('getOrderDetail = GET /api/v1/orders/{orderNo}', async () => {
    const calls = stubFetchCapture()
    await getOrderDetail('OD000001')
    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/api/v1/orders/OD000001')
  })
})

describe('订单状态与动作标签常量（hifi §4.6 单一源）', () => {
  it('状态三态中文标签', () => {
    expect(ORDER_STATUS_LABELS).toEqual({
      IN_PROGRESS: '进行中',
      COMPLETED: '已完成（已订购）',
      CLOSED: '已关闭',
    })
  })

  it('动作标签：创建 / 生效完成 / 终止关闭', () => {
    expect(ORDER_ACTION_LABELS).toEqual({
      CREATE: '创建订单',
      COMPLETE: '合约生效，订单完成',
      CLOSE: '合约终止，订单关闭',
    })
  })

  it('非本方/不存在提示与后端 1011C0002 文案一致（同形提示）', () => {
    expect(ORDER_NOT_FOUND_TIP).toBe('订单不存在或无权查看')
  })
})

describe('订单域错误透传', () => {
  it('1011C0001 重复订购经 ApiError 原样抛出（文案不二次包装）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ code: '1011C0001', message: '该产品已有进行中订单，请勿重复订购', data: null }),
      { status: 409, headers: { 'Content-Type': 'application/json' } })))
    await expect(createOrder({
      productId: 15,
      templateNo: 'CT000001',
      templateVersionNo: 1,
      requestId: 'req-dup',
      clauseValues: { slots: {}, strategy: {} },
    })).rejects.toMatchObject({ code: '1011C0001', message: '该产品已有进行中订单，请勿重复订购' })
  })
})
