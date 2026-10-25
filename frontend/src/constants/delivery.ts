/**
 * WBS-3.5.6 交付域常量（hifi §2.2 / §8）：状态 / 形态 / 结论 / 作业中文标签 + tag 类型 +
 * 提示文案 + 错误码同形提示组。枚举值集与码值域 = 编码段实测锚定
 * （SandboxStatus / SandboxResultStatus / SandboxJobStatus / DeliveryType 后端枚举 +
 * 1009 段错误码 DeliveryErrorCodes / SandboxErrorCodes 全量实读）。
 */

/** 交付形态（后端 DeliveryType 枚举；SANDBOX_EXPORT 仅导出链产生，发起面只有 API/FILE）。 */
export const DELIVERY_TYPE_LABELS: Record<string, string> = {
  API: 'API 交付',
  FILE: '文件交付',
  SANDBOX_EXPORT: '沙箱导出',
}

export const DELIVERY_TYPE_TAG_TYPES: Record<string, string> = {
  API: 'success',
  FILE: 'primary',
  SANDBOX_EXPORT: 'warning',
}

/** 交付形态选项（发起弹窗单选；不含 SANDBOX_EXPORT——导出由沙箱结果物链路产生）。 */
export const DELIVERY_TYPE_OPTIONS = [
  { value: 'API', label: DELIVERY_TYPE_LABELS.API },
  { value: 'FILE', label: DELIVERY_TYPE_LABELS.FILE },
]

/** 沙箱任务状态（后端 SandboxStatus 枚举：RUNNING/STOPPED/EXPIRED）。 */
export const SANDBOX_STATUS_LABELS: Record<string, string> = {
  RUNNING: '使用中',
  STOPPED: '已关闭',
  EXPIRED: '已到期',
}

export const SANDBOX_STATUS_TAG_TYPES: Record<string, string> = {
  RUNNING: 'success',
  STOPPED: 'info',
  EXPIRED: 'danger',
}

/** 沙箱状态选项（过滤下拉）。 */
export const SANDBOX_STATUS_OPTIONS = Object.entries(SANDBOX_STATUS_LABELS).map(
  ([value, label]) => ({ value, label }),
)

/**
 * 结果物审核状态（空值 = 未登记提交审核——工作区文件存在而未提交，后端四字段为 null；
 * PENDING/APPROVED/REJECTED = 后端 SandboxResultStatus 枚举）。
 */
export const RESULT_STATUS_LABELS: Record<string, string> = {
  '': '未登记',
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}

export const RESULT_STATUS_TAG_TYPES: Record<string, string> = {
  '': 'info',
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
}

/** 审核留痕结论展示（后端 SandboxResultStatus 值域——留痕行 conclusion 字段）。 */
export const REVIEW_CONCLUSION_LABELS: Record<string, string> = {
  APPROVED: '通过',
  REJECTED: '驳回',
}

/** 审核弹窗结论单选值（后端 G3 请求体收 APPROVE/REJECT 字符串）。 */
export const REVIEW_CONCLUSION_OPTIONS = [
  { value: 'APPROVE', label: '通过' },
  { value: 'REJECT', label: '驳回' },
]

/** 受控作业白名单（后端 application.yml ctds.sandbox.jobs，display-name 同源）。 */
export const JOB_ID_OPTIONS = [
  { value: 'dataset-profile', label: '数据集画像' },
  { value: 'input-inventory', label: '输入清单' },
]

/** 作业状态（后端 SandboxJobStatus 枚举）。 */
export const JOB_STATUS_LABELS: Record<string, string> = {
  RUNNING: '执行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  TIMEOUT: '超时',
}

export const JOB_STATUS_TAG_TYPES: Record<string, string> = {
  RUNNING: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  TIMEOUT: 'info',
}

/**
 * 动作留痕动作名展示（后端 DeliveryAuditActions 值域）——G7 动作留痕读面只读
 * delivery_action_log 表，sandbox.* 动作写 sandbox_action_log 不经本读面
 * （评审修复 F3.3：收敛为实际可达值域，键与 SandboxAuditActions 错位条目删除）。
 */
export const ACTION_LOG_ACTION_LABELS: Record<string, string> = {
  'delivery.create': '发起交付',
  'delivery.read': '查看交付',
  'delivery.governance.read': '治理查看交付',
  'delivery.package.download': '领取加密包',
  'delivery.denied': '操作被拒',
}

/** 留痕结果列（SUCCESS/DENIED/FAILURE）。 */
export const ACTION_LOG_RESULT_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  DENIED: '被拒',
  FAILURE: '失败',
}

// ==== 提示文案（页面源码不散写中文——T14 SourceGuard） ====

/** 治理读面读即留痕提示（Tab1/Tab2 常驻）。 */
export const GOVERNANCE_READ_TRACE_HINT =
  '治理查看将产生留痕记录；每次进入与查询均会被记录'

/** 沙箱唯一出口诚实边界文案（P3 工作台区常驻）。 */
export const SANDBOX_EXIT_BOUNDARY_HINT =
  '沙箱内数据可读可算，数据本体不可带出；唯一出口 = 结果物审核通过后导出'

/** 审核结论不可改提示（审核弹窗常驻）。 */
export const REVIEW_IMMUTABLE_HINT =
  '审核结论一经作出不可修改；如需纠错，由需求方重新提交审核（轮次 +1）'

/** 沙箱申请形态口径提示（申请弹窗常驻；判定在服务端）。 */
export const SANDBOX_FORM_HINT =
  '沙箱仅接受数据集 / 报告 / 模型形态的已生效合约；接口（API）形态合约将被拒绝'

/** 加密包下载成功提示前缀（后附 SM3 校验值，提示比对完整性）。 */
export const PACKAGE_DOWNLOAD_SM3_HINT = '加密包已下载，请以下列 SM3 校验值比对完整性：'

/** 结果物未登记提示（提交审核按钮旁；空值状态语义）。 */
export const RESULT_UNREGISTERED_HINT = '该结果物尚未提交审核'

/** 已出结论行操作列提示（治理面）。 */
export const REVIEW_CONCLUDED_ROW_HINT = '结论已作出，不可修改；纠错走需求方重新提交'

/** PENDING 行导出按钮 tooltip（体验层提示；判定在服务端 1009C0016）。 */
export const EXPORT_DISABLED_PENDING_HINT = '审核中，暂不可导出'

/** REJECTED 行导出按钮 tooltip。 */
export const EXPORT_DISABLED_REJECTED_HINT = '已驳回，重新提交审核通过后方可导出'

/** 未登记行导出按钮 tooltip。 */
export const EXPORT_DISABLED_UNREGISTERED_HINT = '未提交审核，不可导出'

/** PENDING 行提交审核按钮 tooltip。 */
export const SUBMIT_DISABLED_PENDING_HINT = '审核中，不可重复提交'

/** 关闭任务确认文案。 */
export const CLOSE_SANDBOX_CONFIRM_TIP = '关闭后任务不可再使用（作业 / 提交 / 导出均会被拒绝），确认关闭？'

/** 文件交付缺文件标识前置拦截提示。 */
export const FILE_ID_REQUIRED_TIP = '文件交付需填写文件标识'

/** 关闭任务成功提示。 */
export const CLOSE_SANDBOX_SUCCESS_TIP = '任务已关闭'

/** 配额摘要后缀说明（P2 列表列头）。 */
export const SANDBOX_QUOTA_COLUMN_LABEL = '配额（CPU·内存·时长）'

// ==== 错误码同形提示组（T15 同形矩阵；后端文案原样透传，此处仅前端体验层提示锚） ====

/**
 * 交付域九位错误码 → 业务可读提示（同一码全站唯一一条；后端 message 原样展示为权威，
 * 本组用于测试锚定与交互层兜底文案）。值域 = DeliveryErrorCodes / SandboxErrorCodes
 * 全量 + 跨域透传码（1008C0013/1008C0020 合约域权威）。
 */
export const DELIVERY_ERROR_HINTS: Record<string, string> = {
  '1009C0003': '非合约需求方不可发起交付调用',
  '1009C0005': '交付请求参数不合法',
  '1009C0006': '交付形态或范围与合约约定不符',
  '1009C0007': '交付记录不存在或无权查看',
  '1009C0008': '文件超出交付上限',
  '1009C0009': '仅合约需求方可领取交付包',
  '1009C0010': '沙箱任务不存在或无权查看',
  '1009C0011': '沙箱任务当前状态不允许该操作',
  '1009C0012': '原始数据不可直接导出',
  '1009C0013': '沙箱任务数量已达上限',
  '1009C0014': '作业不在允许清单内',
  '1009C0015': '结果物不存在或无权查看',
  '1009C0016': '结果物当前状态不允许该操作',
  '1008C0013': '合约当前状态不允许该操作',
  '1008C0020': '越界使用被拒绝，触发要素见留痕',
}
