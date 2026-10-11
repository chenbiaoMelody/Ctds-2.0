/**
 * WBS-3.4.7 合约域常量（hifi §2）：合约状态（六态）/ 终止类型（三支）/ 我的角色 / 模板类型
 * （三类）/ 模板状态（两态）/ 合约留痕动作码（12 值域）/ 模板维护留痕动作码（5 值域）/
 * 策略要素键与显示名（五要素）——**页面源码不得散写这些中文**（T20 源集守卫锚定）。
 * 统一提示文案常量：同形拒绝（1008C0001/C0002/C0010/C0012/C0017）与未入驻三语境
 * （1008C0003 浏览/发起/维护）各为**同一条**提示（与后端文案逐字一致——防存在性探测口径
 * 一致，前端不得区分"不存在/无权/停用/未上架"）；诚实边界两则常驻"策略与使用"区（§6.6-7）。
 */

// ==== 合约状态（六态） ====

export const CONTRACT_STATUS_LABELS: Record<string, string> = {
  NEGOTIATING: '协商中',
  PENDING_SIGNATURE: '待签署',
  PARTIALLY_SIGNED: '部分签署',
  EFFECTIVE: '已生效',
  COMPLETED: '已完结',
  TERMINATED: '已终止',
}

export const CONTRACT_STATUS_TAG_TYPES: Record<string, string> = {
  NEGOTIATING: 'warning',
  PENDING_SIGNATURE: 'primary',
  PARTIALLY_SIGNED: 'warning',
  EFFECTIVE: 'success',
  COMPLETED: 'info',
  TERMINATED: 'danger',
}

// ==== 终止类型（三支） ====

export const TERMINATION_TYPE_LABELS: Record<string, string> = {
  NEGOTIATION_TERMINATED: '协商终止',
  SIGNATURE_REFUSED: '拒签终止',
  GOVERNANCE_FORCE_TERMINATED: '治理强制终止',
}

// ==== 我的角色 ====

export const MY_ROLE_LABELS: Record<string, string> = {
  PROVIDER: '提供方',
  REQUESTER: '需求方',
}

// ==== 模板类型（三类）/ 模板状态（两态） ====

export const TEMPLATE_TYPE_LABELS: Record<string, string> = {
  PUBLIC_DATA_AUTHORIZATION: '公共数据授权',
  API_CALL: 'API调用',
  PRIVACY_COMPUTING: '隐私计算',
}

export const TEMPLATE_TYPE_OPTIONS = Object.entries(TEMPLATE_TYPE_LABELS)
  .map(([code, label]) => ({ value: code, label }))

export const TEMPLATE_STATUS_LABELS: Record<string, string> = {
  ENABLED: '启用中',
  DISABLED: '已停用',
}

export const TEMPLATE_STATUS_TAG_TYPES: Record<string, string> = {
  ENABLED: 'success',
  DISABLED: 'info',
}

// ==== 留痕动作码（合约统一留痕 12 值域 / 模板维护留痕 5 值域） ====

export const ACTION_LOG_ACTION_LABELS: Record<string, string> = {
  CREATE: '发起',
  PROPOSE: '提案',
  CONFIRM: '确认',
  TERMINATE_NEGOTIATION: '协商终止',
  SIGN: '签署',
  REFUSE_SIGN: '拒签终止',
  ATTEST: '存证',
  RELEASE_CONSENT: '合意解除',
  FORCE_TERMINATE: '强制终止',
  GOVERNANCE_VIEW: '治理查看',
  VERIFY_SIGNATURE_FAILED: '验签异常',
  DENIED_ACCESS: '越权拒绝',
}

export const TEMPLATE_ACTION_LOG_LABELS: Record<string, string> = {
  CREATE: '新增',
  REVISE: '修订',
  ENABLE: '启用',
  DISABLE: '停用',
  DENIED_MANAGE: '维护被拒',
}

// ==== 策略要素键与显示名（五要素；键与后端策略文档字段一致） ====

export const POLICY_ELEMENT_LABELS: Record<string, string> = {
  quota: '次数上限',
  term: '使用期限',
  purpose: '用途',
  territory: '域内范围',
  noRedistribution: '禁止再分发',
}

/** 模拟结论"策略来源"显示名（值域 EFFECTIVE / DRAFT——后端 PolicySimulationService）。 */
export const SIMULATION_SOURCE_LABELS: Record<string, string> = {
  EFFECTIVE: '生效策略',
  DRAFT: '策略草稿',
}

/** 内容哈希工具提示（hifi §6.4-B"对齐工具提示"的实施文案——勘误 E1）。 */
export const CONTENT_HASH_HINT = '内容哈希与存证摘要一致，即表明合约文本未被篡改'

// ==== 统一提示文案（与后端错误文案逐字一致；页面禁止散写——T20 反向探针） ====

export const TEMPLATE_NOT_ACCESSIBLE_TIP = '模板不存在或不可用'
export const TEMPLATE_MAINTAIN_FORBIDDEN_TIP = '无权进行模板维护操作'
export const CONTRACT_NOT_ACCESSIBLE_TIP = '合约不存在或不可见'
export const PRODUCT_NOT_DEALABLE_TIP = '产品不存在或未在架，无法发起合约'
export const TEMPLATE_NOT_INITIABLE_TIP = '所选模板不可用于发起合约'
export const ADMISSION_REQUIRED_BROWSE_TIP = '主体未入驻或不存在，无法浏览模板'
export const ADMISSION_REQUIRED_INITIATE_TIP = '主体未入驻或不存在，无法发起合约'
export const ADMISSION_REQUIRED_MAINTAIN_TIP = '主体未入驻或不存在，无法维护模板'

/** 诚实边界两则（§6.6-7：常驻"策略与使用"区，不折叠隐藏——ADR-021 §2.9）。 */
export const SIMULATION_HONESTY_TIP = '模拟结论不是放行承诺；正式放行以真实执行通道为准'
export const EXECUTION_DEMO_TIP = '受控执行为演示期受控入口，正式交付链接入后同判定面'

/** 演示身份档位提示（§4：运营档打开参与方面动作时的体验层引导）。 */
export const OPERATOR_MODE_PARTICIPANT_TIP = '当前为平台运营方档，参与方动作请切回普通主体档'
