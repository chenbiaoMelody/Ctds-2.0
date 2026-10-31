import { makeDomainJson, pageQuery } from './client'
import { contractRolesHeader } from '../stores/demoIdentity'
import type { PageData } from './types'

/**
 * 订单域 API（WBS-3.7.3 hifi §1 契约表 / §4.1）：W-OR1 创建订单 + R-OR1 本方列表 +
 * R-OR2 详情（R-OR3 治理检索界面归 3.7.7，本卡不封装）。角色头走合约域映射
 * （contractRolesHeader——订单端点权限点复用 contract.*，Q1-A）。
 * 后端 1011 段错误经 ApiError 原样抛出（文案服务端单点，不二次包装）。
 */
const orderJson = makeDomainJson(contractRolesHeader)

/** 订单条目（列表/详情公共字段；hifi §1.4 OrderSummary）。 */
export interface OrderSummary {
  orderNo: string
  status: string
  contractNo: string
  contractStatus: string | null
  productId: number
  productName: string
  providerSubjectNo: string
  requesterSubjectNo: string
  pricingModel: string
  priceAmount: string | null
  createdAt: string
  myRole?: 'REQUESTER' | 'PROVIDER'
}

/** 订单留痕时间线行（用户读面不含 DENIED_ACCESS——后端已过滤；类型保留全枚举供前端纵深过滤）。 */
export interface OrderActionView {
  action: 'CREATE' | 'COMPLETE' | 'CLOSE' | 'DENIED_ACCESS'
  fromStatus: string | null
  toStatus: string | null
  reason: string | null
  operatorSubjectNo: string | null
  createdAt: string
}

/** 订单详情（= Summary + 模板快照 + 关闭信息 + 时间线；hifi §1.4 OrderDetail）。 */
export interface OrderDetail extends OrderSummary {
  templateNo: string
  templateVersionNo: number
  closedReason: string | null
  closedAt: string | null
  actions: OrderActionView[]
}

/** W-OR1 创建订单响应（hifi §1.4 OrderResult）。 */
export interface OrderResult {
  orderNo: string
  status: string
  contractNo: string
  contractStatus: string
  productId: number
  productName: string
  providerSubjectNo: string
  requesterSubjectNo: string
  pricingModel: string
  priceAmount: string | null
  templateNo: string
  templateVersionNo: number
  createdAt: string
}

/** W-OR1 创建订单请求（requestId 客户端弹窗生成一次——幂等键 = 需方 + requestId）。 */
export interface CreateOrderRequest {
  productId: number
  templateNo: string
  templateVersionNo: number
  requestId: string
  clauseValues: { slots: Record<string, string>; strategy: Record<string, unknown> }
}

/** W-OR1 创建订单（201 = 订单 + 合约草案同事务落库；B3-1~B3-4）。 */
export function createOrder(command: CreateOrderRequest): Promise<OrderResult> {
  return orderJson('/api/v1/orders', { method: 'POST', body: JSON.stringify(command) })
}

/** R-OR1 本方订单分页（需方或供方；B4-1 + B12-1 双角色读，myRole 标注）。 */
export function listMyOrders(query: {
  pageNum: number
  pageSize: number
  status?: string
  productId?: number
  contractNo?: string
}): Promise<PageData<OrderSummary>> {
  const extra: Record<string, string> = {}
  if (query.status) extra.status = query.status
  if (query.productId != null) extra.productId = String(query.productId)
  if (query.contractNo) extra.contractNo = query.contractNo
  return orderJson(`/api/v1/orders/mine${pageQuery(query.pageNum, query.pageSize, extra)}`)
}

/** R-OR2 订单详情（仅需方/供方可读；非本方与不存在同形 1011C0002——B4-4）。 */
export function getOrderDetail(orderNo: string): Promise<OrderDetail> {
  return orderJson(`/api/v1/orders/${encodeURIComponent(orderNo)}`)
}
