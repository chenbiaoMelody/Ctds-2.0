import { makeDomainJson, pageQuery } from './client'
import { billingRolesHeader } from '../stores/demoIdentity'
import type { PageData } from './types'

/**
 * 计费域 API（WBS-3.7.7 hifi §1 契约表 / §2.1 函数表）：
 * 既有 8 端点（B-R1~B-R4 / B-W1~B-W4，3.7.6 已交付）+ 新增 1 个只读端点 R-M4（计量全量流水）。
 * 角色头走 `billingRolesHeader()`（运营档 admin / 普通档 requester,provider，§5）；
 * 金额一律字符串（服务端 `toPlainString` 出站、前端不做数值换算，§1 响应封套）；
 * 后端 1011 段错误经 ApiError 原样抛出（文案服务端单点，不二次包装，§7）。
 */
const billingJson = makeDomainJson(billingRolesHeader)

/** 账单摘要（B-R1 / B-R3 列表项；hifi §1 BillSummary 逐字对齐）。 */
export interface BillSummary {
  billNo: string
  billType: string
  contractNo: string
  requesterNo: string
  providerNo: string
  productId: number
  productName: string
  period: string
  pricingModel: string
  amount: string
  status: string
  correctionOfBillNo: string | null
  createdAt: string
  myRole: string | null
}

/** 账单明细行（B-R2 明细区）。 */
export interface BillDetailLine {
  lineNo: number
  lineType: string
  description: string | null
  meteringFlowId: number | null
  sourceType: string | null
  sourceNo: string | null
  occurredAt: string | null
  quantity: number | null
  unitPrice: string | null
  amount: string | null
}

/** 账单动作留痕行（B-R2 留痕区）。 */
export interface BillAction {
  action: string
  fromStatus: string | null
  toStatus: string | null
  reason: string | null
  operatorSubjectNo: string | null
  createdAt: string
}

/** 账单详情（B-R2；= 摘要 + 金额构成快照 + 明细 + 留痕 + 更正链）。 */
export interface BillDetail {
  billNo: string
  billType: string
  contractNo: string
  requesterNo: string
  providerNo: string
  productId: number
  productName: string
  period: string
  pricingModel: string
  quantity: number | null
  unitPrice: string | null
  baseAmount: string | null
  shareRate: string | null
  amount: string
  status: string
  correctionOfBillNo: string | null
  createdAt: string
  details: BillDetailLine[]
  actions: BillAction[]
  correctionBillNos: string[]
}

/** 对账差异条目（B-R4 / B-W4：type / leg / expected / actual / detail）。 */
export interface ReconciliationDifference {
  type: string
  leg: string | null
  expected: string
  actual: string
  detail: string | null
}

/** 对账结论（B-R4 / B-W4：四方数值 + 账单合计 + 复算金额 + 结论 + 差异明细）。 */
export interface Reconciliation {
  id: number
  contractNo: string
  period: string
  counterUsedCount: number
  usageLogAllowedCount: number
  meteringFlowCount: number
  usageLogAllowedAllTime: number
  meteringFlowAllTime: number
  billQuantity: number | null
  billTotalAmount: string | null
  recomputedAmount: string | null
  conclusion: string
  differences: ReconciliationDifference[]
  createdBy: string | null
  createdAt: string
}

/** 计量流水行（R-M4 新增端点；九字段最小投影）。 */
export interface MeteringFlowRow {
  id: number
  contractNo: string
  requesterNo: string
  sourceType: string
  sourceNo: string
  action: string
  resource: string | null
  occurredAt: string
  createdAt: string
}

/** B-R3 运营全量检索条件（七维过滤 + 分页）。 */
export interface BillGovernanceQuery {
  pageNum: number
  pageSize: number
  contractNo?: string
  period?: string
  productId?: number
  requesterNo?: string
  providerNo?: string
  status?: string
  billType?: string
}

/** B-R1 本方账单检索条件。 */
export interface MyBillQuery {
  pageNum: number
  pageSize: number
  contractNo?: string
  period?: string
  productId?: number
}

/** R-M4 全量流水检索条件（五维过滤 + 分页）。 */
export interface MeteringFlowQuery {
  pageNum: number
  pageSize: number
  contractNo?: string
  requesterNo?: string
  sourceType?: string
  from?: string
  to?: string
}

/** B-W1 手动出账请求体（period 缺省由服务端按上一自然月兜底）。 */
export interface OutbillRequest {
  contractNo: string
  period?: string
  agreedTransactionAmount?: string
}

/** B-W3 更正请求体（amount 可负；字符串提交避免浮点误差）。 */
export interface CorrectionRequest {
  amount: string
  reason: string
}

/** 过滤项拼装（空值不参与查询串；键序 = hifi §1 参数表顺序）。 */
function filters(entries: Record<string, string | number | undefined>): Record<string, string> {
  const extra: Record<string, string> = {}
  for (const [key, value] of Object.entries(entries)) {
    if (value !== undefined && value !== null && value !== '') {
      extra[key] = String(value)
    }
  }
  return extra
}

/** B-R3 运营全量账单分页（B12-2 账单读面）。 */
export function listGovernanceBills(query: BillGovernanceQuery): Promise<PageData<BillSummary>> {
  const extra = filters({
    contractNo: query.contractNo,
    period: query.period,
    productId: query.productId,
    requesterNo: query.requesterNo,
    providerNo: query.providerNo,
    status: query.status,
    billType: query.billType,
  })
  return billingJson(`/api/v1/billing/bills/governance${pageQuery(query.pageNum, query.pageSize, extra)}`)
}

/** B-R2 账单详情（运营与门户共用；身份头决定可见面，非本方与不存在同码同文）。 */
export function getBillDetail(billNo: string): Promise<BillDetail> {
  return billingJson(`/api/v1/billing/bills/${encodeURIComponent(billNo)}`)
}

/** B-W1 手动出账（分成档须携带约定交易额；重复出账 409 1011C0010）。 */
export function outbillBill(body: OutbillRequest): Promise<BillDetail> {
  return billingJson('/api/v1/billing/bills', { method: 'POST', body: JSON.stringify(body) })
}

/** B-W2 运营确认（ISSUED → CONFIRMED；冻结 409 1011C0011）。 */
export function confirmBill(billNo: string): Promise<BillDetail> {
  return billingJson(`/api/v1/billing/bills/${encodeURIComponent(billNo)}/confirm`, { method: 'POST' })
}

/** B-W3 更正单（原单零改动；金额字符串、可负）。 */
export function createCorrection(billNo: string, body: CorrectionRequest): Promise<BillDetail> {
  return billingJson(`/api/v1/billing/bills/${encodeURIComponent(billNo)}/corrections`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/** B-R1 本方账单分页（需方或供方；myRole 由服务端判定）。 */
export function listMyBills(query: MyBillQuery): Promise<PageData<BillSummary>> {
  const extra = filters({
    contractNo: query.contractNo,
    period: query.period,
    productId: query.productId,
  })
  return billingJson(`/api/v1/billing/bills/mine${pageQuery(query.pageNum, query.pageSize, extra)}`)
}

/** B-R4 对账结论分页（参与方或运营；period 可选）。 */
export function listReconciliations(
  contractNo: string,
  query: { pageNum: number; pageSize: number; period?: string },
): Promise<PageData<Reconciliation>> {
  const extra = filters({ period: query.period })
  return billingJson(
    `/api/v1/billing/contracts/${encodeURIComponent(contractNo)}/reconciliations`
    + pageQuery(query.pageNum, query.pageSize, extra),
  )
}

/** B-W4 执行对账（period 必填走查询串；差异 → fail-closed 冻结）。 */
export function runReconciliation(contractNo: string, period: string): Promise<Reconciliation> {
  const params = new URLSearchParams({ period })
  return billingJson(
    `/api/v1/billing/contracts/${encodeURIComponent(contractNo)}/reconciliations?${params.toString()}`,
    { method: 'POST' },
  )
}

/** R-M4 运营全量计量流水（新增只读端点；occurred_at 倒序稳定分页）。 */
export function listGovernanceMeteringFlows(query: MeteringFlowQuery): Promise<PageData<MeteringFlowRow>> {
  const extra = filters({
    contractNo: query.contractNo,
    requesterNo: query.requesterNo,
    sourceType: query.sourceType,
    from: query.from,
    to: query.to,
  })
  return billingJson(`/api/v1/metering/flows/governance${pageQuery(query.pageNum, query.pageSize, extra)}`)
}
