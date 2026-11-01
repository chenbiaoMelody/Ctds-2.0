import { makeDomainJson } from './client'
import { deliveryRolesHeader } from '../stores/demoIdentity'

/**
 * 评价域 API（WBS-3.7.4 hifi §1 契约表 / §5.1）：W-EV1 提交/更新（upsert 一人一评）+
 * R-EV1 统计 + R-EV2 文字列表 + R-EV3 本人回显 + G-EV1 运营隐藏。角色头走交付域映射
 * （deliveryRolesHeader——评价端点权限点复用 delivery.*，Q4-A）。
 * 后端 1011C0005~0007 错误经 ApiError 原样抛出（文案服务端单点，不二次包装）。
 */
const reviewJson = makeDomainJson(deliveryRolesHeader)

/** 评价条目（R-EV2 列表 / R-EV3 回显公共字段；hidden 如实回传，列表面恒 false）。 */
export interface EvaluationItem {
  reviewId: number
  requesterNo: string
  score: number
  comment: string | null
  createdAt: string
  hidden: boolean
}

/** R-EV1 统计响应（仅计未隐藏；averageScore 1 位小数，无评价 = null + 0）。 */
export interface EvaluationStats {
  productId: number
  averageScore: number | null
  reviewCount: number
}

/** R-EV2 文字评价列表响应（时间倒序全列，Q3-A 最小口径）。 */
export interface EvaluationList {
  productId: number
  items: EvaluationItem[]
}

/** W-EV1 提交/更新响应（updated: 首次 false / 更新 true）。 */
export interface ReviewResult {
  reviewId: number
  productId: number
  score: number
  comment: string | null
  createdAt: string
  updated: boolean
}

/** W-EV1 提交/更新请求（requestId 客户端生成 UUID——契约透传字段，Q8-A 零幂等组件）。 */
export interface SubmitEvaluationRequest {
  productId: number
  score: number
  comment?: string
  requestId: string
}

/** G-EV1 隐藏响应。 */
export interface HideResult {
  reviewId: number
  hidden: boolean
}

/** W-EV1 提交/更新评价（B5-1 资格服务端硬校验；B5-2 一人一评 upsert）。 */
export function submitEvaluation(command: SubmitEvaluationRequest): Promise<ReviewResult> {
  return reviewJson('/api/v1/evaluations', { method: 'POST', body: JSON.stringify(command) })
}

/** R-EV1 产品评价统计。 */
export function fetchEvaluationStats(productId: number): Promise<EvaluationStats> {
  return reviewJson(`/api/v1/evaluations/products/${productId}/stats`)
}

/** R-EV2 文字评价列表（时间倒序全列）。 */
export function fetchProductEvaluations(productId: number): Promise<EvaluationList> {
  return reviewJson(`/api/v1/evaluations/products/${productId}`)
}

/** R-EV3 本人评价回显（未评价 = null）。 */
export function fetchMyEvaluation(productId: number): Promise<EvaluationItem | null> {
  return reviewJson(`/api/v1/evaluations/mine?productId=${productId}`)
}

/** G-EV1 运营隐藏（delivery.governance 仅 admin；留痕不物理删除）。 */
export function hideEvaluation(reviewId: number): Promise<HideResult> {
  return reviewJson(`/api/v1/evaluations/${reviewId}/hide`, { method: 'POST' })
}
