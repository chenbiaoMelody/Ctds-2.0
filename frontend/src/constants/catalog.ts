/**
 * WBS-3.3.6 目录域前端共享常量（`docs/designs/WBS-3.3.6-hifi.md` §2 / §6.7）：
 * 资源类型 / 产品形态 / 定价档 / 产品状态 / 资源状态 / 分级申报 / 留痕动作与结果的
 * 中文标签与 tag 色映射，以及统一提示文案常量。
 * - 页面源码**禁止散写**这些中文字面量（源集守卫 T19）；
 * - **DB-36 Q5-A 界面统一落点**：资源域枚举码（DatasetView.type/status 等）经本模块映射为中文，
 *   与产品面直出中文显示名（ProviderProductView/CatalogProduct* 的 status/productType 等）同屏观感一致；
 * - 同形拒绝三条（1007C0005 / 1007C0011 / 1007C0012）各自**同一条**提示常量（§6.7，防存在性探测）。
 */

// ==== 资源类型 / 产品形态（四类，值 = 后端枚举） ====

export const DATASET_TYPE_LABELS: Record<string, string> = {
  DATASET: '数据集',
  API: 'API接口',
  REPORT: '报告',
  MODEL: '模型',
}

// ==== 定价模型（四档，值 = 后端枚举） ====

export const PRICING_MODEL_LABELS: Record<string, string> = {
  FREE: '免费',
  PER_CALL: '按次',
  MONTHLY: '包月',
  REVENUE_SHARE: '交易额分成',
}

// ==== 产品状态（四态；键 = 状态机枚举，值 = 后端产品面中文显示名，双向复用） ====

export const PRODUCT_STATUS_LABELS: Record<string, string> = {
  DRAFT: '未上架',
  LISTED: '已上架',
  DELISTED: '已下架',
  CANCELLED: '已注销',
}

export const PRODUCT_STATUS_TYPES: Record<string, string> = {
  DRAFT: 'info',
  LISTED: 'success',
  DELISTED: 'warning',
  CANCELLED: 'danger',
}

/**
 * 产品状态 tag 色（产品面出参为中文显示名——DB-36 双轨；按显示名反查枚举键取色）。
 */
export function productStatusType(value: string): string {
  const key = Object.entries(PRODUCT_STATUS_LABELS).find(([, label]) => label === value)?.[0]
  return (key && PRODUCT_STATUS_TYPES[key]) || 'info'
}

/** 「重新上架」动作按钮标签（动作码复用 PUBLISH，UI 面独立标签）。 */
export const REPUBLISH_LABEL = '重新上架'

// ==== 资源状态（两态，键 = 后端枚举） ====

export const DATASET_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '生效中',
  DELETED: '已注销',
}

export const DATASET_STATUS_TYPES: Record<string, string> = {
  ACTIVE: 'success',
  DELETED: 'info',
}

// ==== 分级申报（L1~L4，就高收紧） ====

export const DECLARE_LEVEL_LABELS: Record<string, string> = {
  L1: 'L1',
  L2: 'L2',
  L3: 'L3',
  L4: 'L4',
}

/** 级别选项（登记弹窗按 L1→L4 全档；变更弹窗同列全档，收紧与否以服务端 1007C0004 为准）。 */
export const DECLARE_LEVEL_OPTIONS = ['L1', 'L2', 'L3', 'L4']

// ==== 资源留痕动作与结果（dataset_action_log 值域，V4 注释登记） ====

export const DATASET_ACTION_LABELS: Record<string, string> = {
  REGISTER: '登记',
  UPDATE: '变更',
  CANCEL: '注销',
  DENIED_REGISTER: '登记被拒',
  DENIED_UPDATE: '变更被拒',
  DENIED_CANCEL: '注销被拒',
  DENIED_READ: '读取被拒',
  GOVERNANCE_VIEW: '治理查看',
}

export const ACTION_RESULT_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  DENIED: '被拒',
}

export const ACTION_RESULT_TYPES: Record<string, string> = {
  SUCCESS: 'success',
  DENIED: 'danger',
}

// ==== 产品留痕动作（product_action_log 全值域，V4 注释登记；R15 全值域可见） ====

export const PRODUCT_ACTION_LABELS: Record<string, string> = {
  CREATE: '封装',
  UPDATE: '变更',
  PUBLISH: '上架',
  DELIST: '下架',
  FORCE_DELIST: '强制下架',
  CANCEL: '注销',
  DENIED_CREATE: '封装被拒',
  DENIED_UPDATE: '变更被拒',
  DENIED_PUBLISH: '上架被拒',
  DENIED_DELIST: '下架被拒',
  DENIED_FORCE_DELIST: '强制下架被拒',
  DENIED_CANCEL: '注销被拒',
  GOVERNANCE_VIEW: '治理查看',
}

// ==== 互动留痕动作与结果（product_interaction_log；R16） ====

export const INTERACTION_ACTION_LABELS: Record<string, string> = {
  FAVORITE: '收藏',
  UNFAVORITE: '取消收藏',
  SUBSCRIBE: '订阅',
  UNSUBSCRIBE: '退订',
}

export const INTERACTION_OUTCOME_LABELS: Record<string, string> = {
  SUCCEEDED: '成功',
  DENIED: '被拒',
}

export const INTERACTION_OUTCOME_TYPES: Record<string, string> = {
  SUCCEEDED: 'success',
  DENIED: 'danger',
}

/**
 * 标签回退助手（与 `constants/space.ts` 的 `labelOf` 同形口径，沿 `constants/did.ts` 先例）：
 * 未收录取值原样返回（界面不吞值、不臆造翻译——产品面中文显示名直出即经此回退）；取值缺失显示占位符 "—"。
 */
export function labelOf(map: Record<string, string>, value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  return map[value] ?? value
}

// ==== 统一提示常量（页面一律引用，禁止散写；§6.7） ====

/** 资源同形口径：不存在与无权（1007C0005）共用同一条文案与样式。 */
export const RESOURCE_NOT_ACCESSIBLE_TIP = '资源不存在或无权访问'

/** 在架面同形口径：不存在/未上架/已下架/已注销/非订阅者（1007C0011）共用同一条文案与样式。 */
export const PRODUCT_NOT_ACCESSIBLE_TIP = '产品不存在或未在架'

/** 管理面同形口径：产品不存在或无权操作（1007C0012）共用同一条文案与样式。 */
export const PRODUCT_MANAGE_NOT_FOUND_TIP = '产品不存在或无权操作'

/** 未入驻主体检索/交互被拒的统一业务文案（1007C0006 原样展示；与后端文案逐字一致，防枚举）。 */
export const CATALOG_ADMISSION_REQUIRED_MESSAGE = '主体未入驻或不存在，无法使用统一目录服务'

/** 运营档打开提供方面页面的体验层提示（服务端 1000C0005 原样展示之外的档位引导）。 */
export const OPERATOR_MODE_TIP = '当前为平台运营方档，提供方页面请切回普通主体档'

// ==== 必填前置拦截提示（零请求） ====

export const RESOURCE_NAME_REQUIRED_TIP = '请填写资源名称'
export const RESOURCE_TYPE_REQUIRED_TIP = '请选择资源类型'
export const RESOURCE_INTRO_REQUIRED_TIP = '请填写资源简介'
export const DECLARE_CATEGORY_REQUIRED_TIP = '请填写分类申报'
export const DECLARE_LEVEL_REQUIRED_TIP = '请选择级别申报'
export const SPACE_ID_REQUIRED_TIP = '请填写所属空间编号'
export const SPACE_ID_NUMERIC_TIP = '空间编号须为数字'
export const PRODUCT_NAME_REQUIRED_TIP = '请填写产品名称'
export const PRODUCT_INTRO_REQUIRED_TIP = '请填写产品简介'
export const PRICING_MODEL_REQUIRED_TIP = '请选择定价档'
export const SOURCE_DATASET_REQUIRED_TIP = '请选择来源资源'
export const PRICE_REQUIRED_TIP = '请填写价格数值'
export const FORCE_REASON_REQUIRED_TIP = '请填写强制下架理由'
export const QUERY_ID_REQUIRED_TIP = '请输入编号'

/** 未订阅时变更记录区的引导提示（零请求，T10）。 */
export const CHANGE_LOGS_SUBSCRIPTION_REQUIRED_TIP = '订阅后可查看产品变更记录'

/** 治理页留痕说明（每次直查写 GOVERNANCE_VIEW，提供方在留痕区核对）。 */
export const GOVERNANCE_VIEW_TIP =
  '每次治理查看均会留痕（谁 / 何时 / 看了什么）；提供方可在「资源登记」或「产品上架」页的操作留痕区核对'

// ==== 危险动作二次确认（取消 = 零请求） ====

/** 资源注销二次确认（明示不可恢复）。 */
export function datasetCancelConfirmTip(name: string): string {
  return `注销后不可恢复，确认注销资源「${name}」？`
}

/** 产品注销二次确认（明示不可恢复）。 */
export function productCancelConfirmTip(name: string): string {
  return `注销后不可恢复，确认注销产品「${name}」？`
}

// ==== 空态文案（一律空态展示，不作报错） ====

export const SEARCH_EMPTY_TIP = '没有找到符合条件的产品'
export const FAVORITES_EMPTY_TIP = '暂未收藏任何产品'
export const SUBSCRIPTIONS_EMPTY_TIP = '暂未订阅任何产品'
export const INTERACTION_LOGS_EMPTY_TIP = '暂无互动记录'
export const DATASETS_EMPTY_TIP = '暂无资源'
export const MY_PRODUCTS_EMPTY_TIP = '暂无产品'
export const DATASET_LOGS_EMPTY_TIP = '暂无操作记录'
export const PRODUCT_LOGS_EMPTY_TIP = '暂无操作记录'
