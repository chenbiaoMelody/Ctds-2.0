import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  submitEvaluation,
  fetchEvaluationStats,
  fetchProductEvaluations,
  fetchMyEvaluation,
  hideEvaluation,
} from './review'
import {
  REVIEW_SECTION_TITLE,
  REVIEW_EMPTY_TIP,
  REVIEW_ELIGIBILITY_DENIED_TIP,
  REVIEW_SCORE_INVALID_TIP,
  REVIEW_HIDE_CONFIRM_TEXT,
  REVIEW_SUBMIT_LABEL,
  REVIEW_UPDATE_LABEL,
  REVIEW_ERROR_CODES,
  formatReviewMeta,
} from '../constants/review'
import { setDemoSubject } from './client'
import { setActorMode } from '../stores/demoIdentity'

/**
 * 评价域 API 形态测试（WBS-3.7.4 hifi §1 契约表 / §5.1）：
 * - W-EV1 `POST /api/v1/evaluations`（提交/更新 upsert；角色头 delivery 域映射）；
 * - R-EV1 统计 / R-EV2 文字列表 / R-EV3 本人回显 / G-EV1 运营隐藏；
 * - 常量单一源（hifi §5.1；与后端 1011C0005~0007 文案同源直译）；
 * - 后端 1011 段错误经 ApiError 原样透传（文案服务端单点，不二次包装）。
 */

interface CapturedRequest {
  url: string
  method: string
  headers: Record<string, string>
  body?: string
}

function stubFetchCapture(respondBody: Record<string, unknown> = { code: '0', message: 'success', data: {} }): CapturedRequest[] {
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
    return new Response(JSON.stringify(respondBody),
      { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
  return calls
}

beforeEach(() => {
  setDemoSubject('S20260925000001')
  setActorMode('subject')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('评价域 API 形态（W-EV1 / R-EV1~3 / G-EV1）', () => {
  it('W-EV1 提交：POST /api/v1/evaluations，普通档角色头，body 含 productId/score/comment/requestId', async () => {
    const calls = stubFetchCapture({
      code: '0', message: 'success',
      data: { reviewId: 1, productId: 15, score: 4, comment: '不错', createdAt: '2026-11-01T12:00:00', updated: false },
    })
    const result = await submitEvaluation({
      productId: 15, score: 4, comment: '不错', requestId: 'req-123',
    })
    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/api/v1/evaluations')
    expect(calls[0].method).toBe('POST')
    expect(calls[0].headers['X-Ctds-Roles']).toBe('requester,provider')
    const body = JSON.parse(calls[0].body ?? '{}')
    expect(body).toMatchObject({ productId: 15, score: 4, comment: '不错' })
    expect(body.requestId).toBe('req-123')
    expect(result.reviewId).toBe(1)
    expect(result.updated).toBe(false)
  })

  it('R-EV1 统计：GET /api/v1/evaluations/products/{id}/stats', async () => {
    const calls = stubFetchCapture({
      code: '0', message: 'success', data: { productId: 15, averageScore: 4.3, reviewCount: 4 },
    })
    const stats = await fetchEvaluationStats(15)
    expect(calls[0].url).toBe('/api/v1/evaluations/products/15/stats')
    expect(calls[0].method).toBe('GET')
    expect(stats.averageScore).toBe(4.3)
    expect(stats.reviewCount).toBe(4)
  })

  it('R-EV2 文字列表：GET /api/v1/evaluations/products/{id}', async () => {
    const calls = stubFetchCapture({
      code: '0', message: 'success',
      data: { productId: 15, items: [{ reviewId: 1, requesterNo: 'S-A', score: 4, comment: null, createdAt: '2026-11-01T12:00:00', hidden: false }] },
    })
    const list = await fetchProductEvaluations(15)
    expect(calls[0].url).toBe('/api/v1/evaluations/products/15')
    expect(list.items).toHaveLength(1)
    expect(list.items[0].hidden).toBe(false)
  })

  it('R-EV3 本人回显：GET /api/v1/evaluations/mine?productId=', async () => {
    const calls = stubFetchCapture({ code: '0', message: 'success', data: null })
    const mine = await fetchMyEvaluation(15)
    expect(calls[0].url).toBe('/api/v1/evaluations/mine?productId=15')
    expect(mine).toBeNull()
  })

  it('G-EV1 运营隐藏：POST /api/v1/evaluations/{id}/hide（运营档角色头）', async () => {
    setActorMode('operator')
    const calls = stubFetchCapture({ code: '0', message: 'success', data: { reviewId: 7, hidden: true } })
    const result = await hideEvaluation(7)
    expect(calls[0].url).toBe('/api/v1/evaluations/7/hide')
    expect(calls[0].method).toBe('POST')
    expect(calls[0].headers['X-Ctds-Roles']).toBe('admin')
    expect(result.hidden).toBe(true)
  })

  it('1011C0006 无资格拒绝经 ApiError 原样透传（同码同文，不二次包装）', async () => {
    stubFetchCapture({ code: '1011C0006', message: REVIEW_ELIGIBILITY_DENIED_TIP, data: null })
    await expect(submitEvaluation({ productId: 15, score: 4, requestId: 'req-x' }))
      .rejects.toMatchObject({ code: '1011C0006', message: REVIEW_ELIGIBILITY_DENIED_TIP })
  })

  it('1011C0005 非法评分经 ApiError 原样透传（同码同文，不二次包装）', async () => {
    stubFetchCapture({ code: '1011C0005', message: REVIEW_SCORE_INVALID_TIP, data: null })
    await expect(submitEvaluation({ productId: 15, score: 0, requestId: 'req-y' }))
      .rejects.toMatchObject({ code: '1011C0005', message: REVIEW_SCORE_INVALID_TIP })
  })
})

describe('评价域常量单一源（hifi §5.1；与后端 1011 段文案同源直译）', () => {
  it('区块文案逐字锚定', () => {
    expect(REVIEW_SECTION_TITLE).toBe('产品评价')
    expect(REVIEW_EMPTY_TIP).toBe('暂无评价')
    expect(REVIEW_ELIGIBILITY_DENIED_TIP).toBe('完成对该产品的有效交付后方可评价')
    expect(REVIEW_SCORE_INVALID_TIP).toBe('评分需为 1~5 的整数')
    expect(REVIEW_HIDE_CONFIRM_TEXT).toBe('确认隐藏该评价？隐藏后不再对外展示，操作将留痕。')
    expect(REVIEW_SUBMIT_LABEL).toBe('提交评价')
    expect(REVIEW_UPDATE_LABEL).toBe('更新评价')
  })

  it('错误码同名词表（1011C0005~0007）', () => {
    expect(REVIEW_ERROR_CODES.SCORE_INVALID).toBe('1011C0005')
    expect(REVIEW_ERROR_CODES.ELIGIBILITY_DENIED).toBe('1011C0006')
    expect(REVIEW_ERROR_CODES.REVIEW_NOT_FOUND).toBe('1011C0007')
  })

  it('列表条目元信息格式化：评价人 {requesterNo} · {yyyy-MM-dd HH:mm}', () => {
    expect(formatReviewMeta('S-A', '2026-11-01T12:34:56')).toBe('评价人 S-A · 2026-11-01 12:34')
    expect(formatReviewMeta('S-B', '2026-11-01T08:05:09')).toBe('评价人 S-B · 2026-11-01 08:05')
  })
})
