/**
 * WBS-3.6.7 连接器域常量（hifi §2.2 / §5）：三主态 / 类型 / 在线 / 绑定 / 兼容性 /
 * 版本状态 / 源类型 / 自检失败 / 核验项 / 留痕动作与结果 / 提示文案。
 * 枚举值集与对端字段 = 编码段实测锚定（ConnectorStatus / ConnectorType / BindingStatus /
 * Compatibility / VersionChangeStatus / SourceType / VerificationCheck / ConnectorAuditActions 实读）；
 * 错误文案零内置——一律展示服务端 `ApiError.message`（服务端权威常量）。
 */

/** 连接器状态（后端 ConnectorStatus 枚举：PENDING_REVIEW / REGISTERED / CANCELLED）。 */
export const CONNECTOR_STATUS_LABELS: Record<string, string> = {
  PENDING_REVIEW: '待核验',
  REGISTERED: '已注册',
  CANCELLED: '已注销',
}

export const CONNECTOR_STATUS_TAG_TYPES: Record<string, string> = {
  PENDING_REVIEW: 'warning',
  REGISTERED: 'success',
  CANCELLED: 'info',
}

/** 状态下拉选项（申请列表本页筛选 / 治理列表服务端筛选共用；首项「全部」= 空值不过滤）。 */
export const CONNECTOR_STATUS_OPTIONS = Object.entries(CONNECTOR_STATUS_LABELS).map(
  ([value, label]) => ({ value, label }),
)

export const ALL_OPTION = { value: '', label: '全部' }

export const CONNECTOR_STATUS_FILTER_OPTIONS = [ALL_OPTION, ...CONNECTOR_STATUS_OPTIONS]

/** 连接器类型（V1.0 仅 STANDARD）。 */
export const CONNECTOR_TYPE_LABELS: Record<string, string> = {
  STANDARD: '标准连接器',
}

export const CONNECTOR_TYPE_OPTIONS = Object.entries(CONNECTOR_TYPE_LABELS).map(
  ([value, label]) => ({ value, label }),
)

/** 在线口径（非已注册态一律「—」：健康度只对已注册态有意义）。 */
export const ONLINE_LABELS = {
  online: '在线',
  offline: '离线',
  notApplicable: '—',
}

export const ONLINE_FILTER_OPTIONS = [
  ALL_OPTION,
  { value: 'true', label: ONLINE_LABELS.online },
  { value: 'false', label: ONLINE_LABELS.offline },
]

/** 在线文案：非 REGISTERED → 「—」。 */
export function onlineLabel(status: string, online: boolean): string {
  if (status !== 'REGISTERED') return ONLINE_LABELS.notApplicable
  return online ? ONLINE_LABELS.online : ONLINE_LABELS.offline
}

/** 在线 tag 类型：非 REGISTERED 一律 info（与「—」文案同口径）。 */
export function onlineTagType(status: string, online: boolean): string {
  if (status !== 'REGISTERED') return 'info'
  return online ? 'success' : 'info'
}

/** 绑定状态（后端 BindingStatus 枚举）。 */
export const BINDING_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '已绑定',
  RELEASED: '已解除',
}

/** DID 实时状态（绑定查验结果；CurrentDid 值域 + 不可用/未查询占位）。 */
export const DID_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '有效',
  REVOKED: '已吊销',
  PENDING_ISSUE: '待签发',
  NONE: '未签发',
}

/** 版本兼容性（后端 Compatibility 枚举；升级登记请求值域）。 */
export const COMPATIBILITY_LABELS: Record<string, string> = {
  COMPATIBLE: '兼容',
  INCOMPATIBLE: '不兼容',
}

export const COMPATIBILITY_OPTIONS = Object.entries(COMPATIBILITY_LABELS).map(
  ([value, label]) => ({ value, label }),
)

/** 版本变更状态（后端 VersionChangeStatus 枚举）。 */
export const VERSION_STATUS_LABELS: Record<string, string> = {
  PENDING_CONFIRM: '待确认',
  APPLIED: '已生效',
  VOIDED: '已作废',
}

export const VERSION_STATUS_TAG_TYPES: Record<string, string> = {
  PENDING_CONFIRM: 'warning',
  APPLIED: 'success',
  VOIDED: 'info',
}

/** 源类型（后端 SourceType 枚举；政务共享交换平台 V1.0 = 标准报文模拟源）。 */
export const SOURCE_TYPE_LABELS: Record<string, string> = {
  GOV_EXCHANGE: '政务共享交换平台（模拟源）',
  DATABASE: '数据库',
  OBJECT_STORAGE: '对象存储',
  API_GATEWAY: 'API 网关',
}

export const SOURCE_TYPE_OPTIONS = Object.entries(SOURCE_TYPE_LABELS).map(
  ([value, label]) => ({ value, label }),
)

/** 源自检失败原因（后端三类失败 + 中性兜底）。 */
export const CHECK_FAILURE_LABELS: Record<string, string> = {
  NETWORK_UNREACHABLE: '网络不可达',
  INVALID_CREDENTIAL: '凭据无效',
  INSUFFICIENT_PERMISSION: '权限不足',
}

/** 核验三查项（后端 VerificationCheck 枚举）。 */
export const VERIFICATION_CHECK_LABELS: Record<string, string> = {
  SUBJECT_ADMISSION: '主体资格',
  DID_STATUS: 'DID 有效性',
  COMPLETENESS: '信息完整性',
}

/** 留痕动作（后端 ConnectorAuditActions 值域；未知动作原样显示 code）。 */
export const AUDIT_ACTION_LABELS: Record<string, string> = {
  'connector.apply': '提交申请',
  'connector.amend': '补正',
  'connector.verify.approved': '核验通过',
  'connector.verify.rejected': '核验拒绝',
  'connector.binding.read': '绑定查验',
  'connector.heartbeat': '心跳上报',
  'connector.denied': '拒绝',
  'connector.upgrade.requested': '升级登记（不兼容待确认）',
  'connector.upgrade.applied': '升级生效',
  'connector.upgrade.confirmed': '升级确认',
  'connector.cancelled': '注销',
  'connector.occupancy.register': '在途作业登记',
  'connector.occupancy.release': '在途作业释放',
  'source.register': '源登记',
  'source.amend': '源变更',
  'source.check': '源自检',
  'source.read': '源读取',
  'source.denied': '源拒绝',
}

export const AUDIT_RESULT_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  DENIED: '拒绝',
  FAILURE: '失败',
}

export const AUDIT_RESULT_TAG_TYPES: Record<string, string> = {
  SUCCESS: 'success',
  DENIED: 'danger',
  FAILURE: 'warning',
}

// ==== 提示文案（页面源码不散写中文） ====

/** 健康度阈值说明（规格 §7 Q8-A：在线 = 服务端读时派生，界面不本地计算）。 */
export const HEALTH_THRESHOLD_HINT =
  '在线 = 最近心跳未超过服务端阈值（默认 5 分钟）；状态以服务端返回为准'

/** 自检语义提示（行为 13：自检不等于授权）。 */
export const SOURCE_SELF_CHECK_HINT =
  '自检通过仅证明连得上，不构成业务授权；读取仍受平台判定约束'

/** 政务模拟源标注（规格 §6 边界 3）。 */
export const GOV_SOURCE_MOCK_HINT =
  '政务共享交换平台 V1.0 为标准报文模拟源；真实政务平台生产对接属部署实施期事项'

/** 凭据掩码提示（行为 12：零明文）。 */
export const SOURCE_CREDENTIAL_MASK_HINT = '凭据仅以掩码展示，提交后不再回显'

/** 绑定查验留痕提示（行为 8：查验动作留痕）。 */
export const BINDING_CHECK_TRACE_HINT = '绑定查验将产生服务端留痕'

/** 运营档源接入区隐藏说明（DB-88 挂起，界面不放大规格冲突）。 */
export const OPERATOR_SOURCE_HIDDEN_HINT =
  '运营档不展示源接入区（归属口径挂起，源接入仅属主可见）'

/** 不兼容升级待确认提示。 */
export const UPGRADE_PENDING_TIP = '存在待确认的不兼容升级，确认前当前版本继续有效'

/** DID 实时状态无效提示（绑定查验 verified=false）。 */
export const DID_STATUS_INVALID_TEXT = 'DID 状态无效'

/** 在途作业注销前置提示（行为 10-1）。 */
export const JOB_IN_PROGRESS_HINT = '存在进行中的交付作业，注销将被拒绝'

/** 详情页返回入口文案（治理页来源 / 运营档 → 治理页）。 */
export const BACK_TO_CONNECTOR_MANAGE = '← 返回连接器管理'
export const BACK_TO_CONNECTOR_GOVERNANCE = '← 返回连接器治理'

/** 本页筛选标注（无服务端过滤的列表，不得伪装全量）。 */
export const LOCAL_FILTER_HINT = '本页筛选'

// ==== 空态 / 成功提示 / 确认文案 ====

export const REGISTRATION_EMPTY_TEXT = '暂无注册申请'
export const CONNECTOR_EMPTY_TEXT = '暂无已注册连接器'
export const CONNECTOR_EMPTY_GUIDE = '请先在「注册申请」页提交接入申请并经运营方核验'
export const SOURCE_EMPTY_TEXT = '暂无接入源'
export const VERSION_HISTORY_EMPTY_TEXT = '暂无版本变更记录'
export const AUDIT_LOG_EMPTY_TEXT = '暂无留痕'
export const GOVERNANCE_EMPTY_TEXT = '暂无连接器档案'
export const REVIEW_QUEUE_EMPTY_TEXT = '暂无待核验申请'
export const PENDING_UPGRADE_EMPTY_TEXT = '暂无待确认升级'

export const APPLY_SUCCESS_TIP = '申请已受理'
export const AMEND_SUCCESS_TIP = '补正已提交'
export const WITHDRAW_SUCCESS_TIP = '申请已撤回'
export const HEARTBEAT_SUCCESS_TIP = '心跳已上报'
export const UPGRADE_RECORDED_TIP = '升级已登记'
export const UPGRADE_PENDING_SUCCESS_TIP = '已登记不兼容升级，待运营方确认后生效'
export const CANCEL_SUCCESS_TIP = '连接器已注销'
export const SOURCE_REGISTER_SUCCESS_TIP = '源已登记'
export const SOURCE_AMEND_SUCCESS_TIP = '源已变更'
export const SELF_CHECK_FAILED_TIP = '自检失败'
export const VERIFY_PASSED_TIP = '核验通过'
export const VERIFY_REJECTED_TIP = '核验拒绝'
export const VERIFY_REJECTED_RETRY_HINT = '补正后可重新核验'
export const SELF_CHECK_PASSED = '自检通过'
export const SELF_CHECK_NOT_YET = '尚未自检'
export const CONFIRM_SUCCESS_TIP = '升级已确认生效'

export const WITHDRAW_CONFIRM_TEXT =
  '撤回后该申请将进入已注销终态，且不再出现在运营方待核验队列；是否继续？'
export const CANCEL_CONFIRM_TEXT = '注销为终态且不可逆，连接器编号终身不复用；是否继续？'
export const CONFIRM_UPGRADE_TEXT = '确认后新版本立即生效；是否继续？'

/** 治理概览条（仅当前页统计，不冒充全量）。 */
export const OVERVIEW_BAR_PREFIX = '本页'
export const OVERVIEW_BAR_SUFFIX = '（仅已注册态计入）'
