/**
 * WBS-3.3.6 目录域 API 模块（hifi §1 端点表 / §2 前端模块，29 个端点函数逐一对应）：
 * - 复用 3.3.2~3.3.5 已交付 26 端点（零改动语义）+ Q2-A 新增只读留痕端点 R14/R15/R16；
 * - 经 `apiJson` 统一封装（**不新增 `fetch` 直连**）；
 * - **页面级角色头**：`X-Ctds-Roles: catalogRolesHeader()`（普通档 `provider` / 运营档 `admin`，
 *   覆盖 `apiJson` 的全局演示角色头，hifi §4、T17——普通档不得携带 `admin`）；
 *   空间域下拉调用走 `api/space.ts` 的 `spaceRolesHeader()`（两页级头互不污染）；
 * - 空主体编号 → 前置拦截（**零请求**），沿 3.2.6 E3 口径（hifi §4 边界）。
 */
import { apiJson, ApiError, getDemoSubject } from './client'
import { catalogRolesHeader } from '../stores/demoIdentity'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../constants/space'
import type { PageData } from './types'

/** 分页数据（唯一声明在 `./types`；此处保持既有导出面）。 */
export type { PageData }

// ==== 响应类型（字段与后端出参逐一对齐；产品面状态/形态/定价为中文显示名——DB-36 双轨） ====

export interface CategoryNode {
  categoryCode: string
  categoryName: string
  children: CategoryNode[]
}

/** 目录检索条目（R6；响应不含 status——结果恒已上架）。 */
export interface CatalogProduct {
  productId: number
  productName: string
  intro: string | null
  productType: string
  pricingModel: string
  priceAmount: string | null
  categoryCode: string | null
  categoryName: string | null
  providerSubjectNo: string
  listedAt: string | null
}

/** 产品详情（R7：R6 字段 + status 全文 intro）。 */
export interface CatalogProductDetail extends CatalogProduct {
  status: string
  createdAt: string
}

/** 产品变更留痕（R8：订阅者可见值域六码）。 */
export interface ProductChangeLog {
  action: string
  summary: string | null
  operatorSubjectNo: string
  createdAt: string
}

/** 收藏条目（R9：含读时计算的产品当前状态）。 */
export interface FavoriteItem extends CatalogProduct {
  productStatus: string
  favoritedAt: string | null
}

/** 订阅条目（R10：含读时计算的产品当前状态）。 */
export interface SubscriptionItem extends CatalogProduct {
  productStatus: string
  subscribedAt: string | null
}

/** 资源登记条目（R1：资源域枚举码——type/status 经 constants/catalog.ts 映射中文）。 */
export interface DatasetItem {
  id: number
  dataNo: string
  spaceId: number
  name: string
  type: string
  intro: string
  tags: string[]
  declareCategory: string
  declareLevel: string
  declareImportant: boolean
  status: string
  createdAt: string
}

/** 提供方产品条目（R11/R12：本人产品全状态管理视图 + 治理全量视图）。 */
export interface ProviderProduct {
  productId: number
  productName: string
  intro: string | null
  productType: string
  pricingModel: string
  priceAmount: string | null
  status: string
  providerSubjectNo: string
  datasetId: number
  categoryCode: string | null
  categoryName: string | null
  listedAt: string | null
  createdAt: string
}

/** 资源操作留痕（R14：四要素 + from→to + 拒绝码）。 */
export interface DatasetActionLog {
  id: number
  action: string
  actorSubjectNo: string
  result: string
  reasonCode: string | null
  fromValue: string | null
  toValue: string | null
  createdAt: string
}

/** 产品操作留痕（R15：全值域，含 DENIED_* 与 GOVERNANCE_VIEW）。 */
export interface ProductActionLog {
  id: number
  action: string
  operatorSubjectNo: string
  summary: string | null
  createdAt: string
}

/** 互动留痕（R16：恒仅本人）。 */
export interface InteractionLog {
  id: number
  productId: number
  action: string
  outcome: string
  denyReason: string | null
  createdAt: string
}

/** 词表册（R3）。 */
export interface TagVocabulary {
  vocabularyCode: string
  vocabularyName: string
}

/** 词条（R4：登记提交 termName）。 */
export interface TagTerm {
  termCode: string
  termName: string
}

/** 资源注销确认（W3）。 */
export interface CancellationView {
  datasetId: number
  dataNo: string
  status: string
  cancelled: boolean
}

/** 收藏/订阅动作回执（W4~W7）。 */
export interface FavoriteView {
  productId: number
  favoritedAt: string | null
}

export interface SubscriptionView {
  productId: number
  subscribedAt: string | null
}

// ==== 请求体类型（白名单契约字段） ====

/** 登记资源（W1；tags 提交 termName 数组；declareImportant 恒布尔）。 */
export interface RegisterDatasetPayload {
  name: string
  type: string
  intro: string
  tags: string[]
  declareCategory: string
  declareLevel: string
  declareImportant: boolean
}

/** 变更资源（W2：仅可变白名单字段，均可选但至少一项；级别只能收紧——服务端 1007C0004 为准）。 */
export interface UpdateDatasetPayload {
  intro?: string
  tags?: string[]
  declareCategory?: string
  declareLevel?: string
}

/** 封装产品（W8：免费档不提交 priceAmount；categoryCode 可选缺省继承）。 */
export interface CreateProductPayload {
  datasetId: number
  productName: string
  intro: string
  productType: string
  pricingModel: string
  priceAmount?: string
  categoryCode?: string
}

/** 变更产品（W9：名称不可变，白名单字段）。 */
export interface UpdateProductPayload {
  intro?: string
  productType?: string
  pricingModel?: string
  priceAmount?: string
  categoryCode?: string
}

// ==== 请求封装 ====

/** 页面级角色头（覆盖 apiJson 的全局 `demoRolesHeader()`；T17）。 */
function catalogHeaders(): Record<string, string> {
  return { 'X-Ctds-Roles': catalogRolesHeader() }
}

/** 目录域统一请求：空主体前置拦截（零请求）→ 附页面级角色头 → `apiJson`。 */
function catalogJson<T>(path: string, options: RequestInit = {}): Promise<T> {
  if (!getDemoSubject().trim()) {
    return Promise.reject(new ApiError('1000C0002', DEMO_SUBJECT_REQUIRED_TIP))
  }
  return apiJson<T>(path, {
    ...options,
    headers: { ...catalogHeaders(), ...(options.headers || {}) },
  })
}

function pageQuery(pageNum: number, pageSize: number, extra: Record<string, string> = {}): string {
  const params = new URLSearchParams({ pageNum: String(pageNum), pageSize: String(pageSize), ...extra })
  return `?${params.toString()}`
}

// ==== 门户（R5/R6/R7/R8 + W4~W7） ====

export function listCategories(): Promise<CategoryNode[]> {
  return catalogJson('/api/v1/catalog/categories')
}

export function searchProducts(
  pageNum: number,
  pageSize: number,
  keyword?: string,
  categoryCode?: string,
): Promise<PageData<CatalogProduct>> {
  const extra: Record<string, string> = {}
  if (keyword) extra.keyword = keyword
  if (categoryCode) extra.categoryCode = categoryCode
  return catalogJson(`/api/v1/data-products${pageQuery(pageNum, pageSize, extra)}`)
}

export function getProductDetail(productId: number): Promise<CatalogProductDetail> {
  return catalogJson(`/api/v1/data-products/${productId}`)
}

export function getProductChangeLogs(
  productId: number,
  pageNum: number,
  pageSize: number,
): Promise<PageData<ProductChangeLog>> {
  return catalogJson(`/api/v1/data-products/${productId}/change-logs${pageQuery(pageNum, pageSize)}`)
}

export function listFavorites(pageNum: number, pageSize: number): Promise<PageData<FavoriteItem>> {
  return catalogJson(`/api/v1/catalog/favorites${pageQuery(pageNum, pageSize)}`)
}

export function favoriteProduct(productId: number): Promise<FavoriteView> {
  return catalogJson(`/api/v1/data-products/${productId}/favorite`, { method: 'POST' })
}

export function unfavoriteProduct(productId: number): Promise<FavoriteView> {
  return catalogJson(`/api/v1/data-products/${productId}/favorite`, { method: 'DELETE' })
}

export function listSubscriptions(pageNum: number, pageSize: number): Promise<PageData<SubscriptionItem>> {
  return catalogJson(`/api/v1/catalog/subscriptions${pageQuery(pageNum, pageSize)}`)
}

export function subscribeProduct(productId: number): Promise<SubscriptionView> {
  return catalogJson(`/api/v1/data-products/${productId}/subscription`, { method: 'POST' })
}

export function unsubscribeProduct(productId: number): Promise<SubscriptionView> {
  return catalogJson(`/api/v1/data-products/${productId}/subscription`, { method: 'DELETE' })
}

// ==== 资源域（W1~W3 + R1/R2 + R3/R4 + R14） ====

export function registerDataset(spaceId: number, payload: RegisterDatasetPayload): Promise<DatasetItem> {
  return catalogJson(`/api/v1/data-spaces/${spaceId}/datasets`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function listMyDatasets(
  pageNum: number,
  pageSize: number,
  spaceId?: number,
): Promise<PageData<DatasetItem>> {
  const extra: Record<string, string> = {}
  if (spaceId !== undefined) extra.spaceId = String(spaceId)
  return catalogJson(`/api/v1/datasets/mine${pageQuery(pageNum, pageSize, extra)}`)
}

export function getDataset(datasetId: number): Promise<DatasetItem> {
  return catalogJson(`/api/v1/datasets/${datasetId}`)
}

export function updateDataset(datasetId: number, payload: UpdateDatasetPayload): Promise<DatasetItem> {
  return catalogJson(`/api/v1/datasets/${datasetId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function cancelDataset(datasetId: number): Promise<CancellationView> {
  return catalogJson(`/api/v1/datasets/${datasetId}/cancellation`, {
    method: 'POST',
    body: JSON.stringify({ confirmCancellation: true }),
  })
}

export function listVocabularies(): Promise<TagVocabulary[]> {
  return catalogJson('/api/v1/tag-vocabularies')
}

export function listTerms(
  vocabularyCode: string,
  pageNum: number,
  pageSize: number,
  keyword?: string,
): Promise<PageData<TagTerm>> {
  const extra: Record<string, string> = {}
  if (keyword) extra.keyword = keyword
  return catalogJson(`/api/v1/tag-vocabularies/${vocabularyCode}/terms${pageQuery(pageNum, pageSize, extra)}`)
}

export function listDatasetActionLogs(
  datasetId: number,
  pageNum: number,
  pageSize: number,
): Promise<PageData<DatasetActionLog>> {
  return catalogJson(`/api/v1/datasets/${datasetId}/action-logs${pageQuery(pageNum, pageSize)}`)
}

// ==== 产品域（W8~W13 + R11/R15） ====

export function createProduct(payload: CreateProductPayload): Promise<ProviderProduct> {
  return catalogJson('/api/v1/data-products', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updateProduct(productId: number, payload: UpdateProductPayload): Promise<ProviderProduct> {
  return catalogJson(`/api/v1/data-products/${productId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function publishProduct(productId: number): Promise<ProviderProduct> {
  return catalogJson(`/api/v1/data-products/${productId}/publish`, { method: 'POST' })
}

export function delistProduct(productId: number): Promise<ProviderProduct> {
  return catalogJson(`/api/v1/data-products/${productId}/delist`, { method: 'POST' })
}

export function cancelProduct(productId: number): Promise<ProviderProduct> {
  return catalogJson(`/api/v1/data-products/${productId}/cancellation`, {
    method: 'POST',
    body: JSON.stringify({ confirmCancellation: true }),
  })
}

export function listMyProducts(pageNum: number, pageSize: number): Promise<PageData<ProviderProduct>> {
  return catalogJson(`/api/v1/data-products/mine${pageQuery(pageNum, pageSize)}`)
}

export function forceDelistProduct(productId: number, forceReason: string): Promise<ProviderProduct> {
  return catalogJson(`/api/v1/data-products/${productId}/force-delist`, {
    method: 'POST',
    body: JSON.stringify({ forceReason }),
  })
}

// ==== 治理与互动留痕（R12/R13/R15/R16） ====

export function getProductGovernance(productId: number): Promise<ProviderProduct> {
  return catalogJson(`/api/v1/data-products/${productId}/governance`)
}

export function getDatasetGovernance(datasetId: number): Promise<DatasetItem> {
  return catalogJson(`/api/v1/datasets/${datasetId}/governance`)
}

export function listProductActionLogs(
  productId: number,
  pageNum: number,
  pageSize: number,
): Promise<PageData<ProductActionLog>> {
  return catalogJson(`/api/v1/data-products/${productId}/action-logs${pageQuery(pageNum, pageSize)}`)
}

export function listInteractionLogs(pageNum: number, pageSize: number): Promise<PageData<InteractionLog>> {
  return catalogJson(`/api/v1/catalog/interaction-logs${pageQuery(pageNum, pageSize)}`)
}
