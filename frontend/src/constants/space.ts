/**
 * WBS-3.2.6 空间域前端共享常量（`docs/designs/WBS-3.2.6-hifi.md` §2 / §6.6）：
 * 状态 / 角色 / 成员状态 / 准入形态与状态 / 场景类型 / 参与方范围 / 可见性 /
 * 策略来源三态与策略目录值 / 留痕动作与结果的中文标签与 tag 色，以及统一提示文案常量。
 * - 页面源码**禁止散写**这些中文字面量（源集守卫 T29）；
 * - 键值与后端枚举一一对齐（SceneType / AccessMode / Visibility / MemberRole / MemberStatus /
 *   AdmissionType / AdmissionStatus / PolicyProvenance / ActionResult / TargetType）；
 * - 非成员同形口径：`1006C0004` 与无权（`1006C0007`）共用**同一条**提示常量（T30）。
 */

// ==== 空间状态（SpaceStatus） ====

export const SPACE_STATUS_LABELS: Record<string, string> = {
  CREATED: '已创建（未启用）',
  ACTIVE: '已启用',
  FROZEN: '已冻结',
  DISSOLVED: '已解散',
}

export const SPACE_STATUS_TYPES: Record<string, string> = {
  CREATED: 'info',
  ACTIVE: 'success',
  FROZEN: 'warning',
  DISSOLVED: 'danger',
}

// ==== 成员角色与状态（MemberRole / MemberStatus） ====

export const MEMBER_ROLE_LABELS: Record<string, string> = {
  OWNER: '所有者',
  ADMIN: '管理员',
  MEMBER: '成员',
}

export const MEMBER_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '生效中',
  LEFT: '已退出',
  REMOVED: '已移除',
}

export const MEMBER_STATUS_TYPES: Record<string, string> = {
  ACTIVE: 'success',
  LEFT: 'info',
  REMOVED: 'danger',
}

// ==== 准入形态与状态（AdmissionType / AdmissionStatus） ====

export const ADMISSION_TYPE_LABELS: Record<string, string> = {
  APPLICATION: '申请',
  INVITATION: '邀请',
}

export const ADMISSION_STATUS_LABELS: Record<string, string> = {
  PENDING_APPROVAL: '待审批',
  PENDING_CONFIRMATION: '待确认',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
  DECLINED: '已谢绝',
  CANCELLED: '已取消',
}

export const ADMISSION_STATUS_TYPES: Record<string, string> = {
  PENDING_APPROVAL: 'warning',
  PENDING_CONFIRMATION: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  DECLINED: 'info',
  CANCELLED: 'info',
}

// ==== 场景类型（SceneType） / 参与方范围（AccessMode） / 可见性（Visibility） ====

export const SCENE_TYPE_LABELS: Record<string, string> = {
  FINTECH: '普惠金融',
  MEDICAL: '医疗验证',
  OTHER: '其他',
}

export const ACCESS_MODE_LABELS: Record<string, string> = {
  OPEN: '公开',
  APPROVAL: '审批制',
  INVITE: '邀请制',
}

export const VISIBILITY_LABELS: Record<string, string> = {
  PUBLIC: '公开',
  PRIVATE: '不公开',
}

// ==== 策略来源三态（PolicyProvenance） ====

export const POLICY_PROVENANCE_LABELS: Record<string, string> = {
  INHERITED: '继承自平台',
  SPACE_EFFECTIVE: '空间级生效',
  SPACE_NOT_EFFECTIVE_TAKE_STRICTER: '空间覆盖未生效（取严）',
}

export const POLICY_PROVENANCE_TYPES: Record<string, string> = {
  INHERITED: 'info',
  SPACE_EFFECTIVE: 'success',
  SPACE_NOT_EFFECTIVE_TAKE_STRICTER: 'warning',
}

// ==== 策略目录三键与封闭值域（PolicyCatalog，与后端 `PolicyCatalog` 逐键对齐） ====

export interface PolicyCatalogOption {
  value: string
  label: string
}

export interface PolicyCatalogEntry {
  entryKey: string
  displayName: string
  options: PolicyCatalogOption[]
}

export const POLICY_CATALOG: PolicyCatalogEntry[] = [
  {
    entryKey: 'data.visibility',
    displayName: '数据可见范围',
    options: [
      { value: 'ALL_PLATFORM', label: '全平台可见' },
      { value: 'SPACE_MEMBER', label: '仅空间成员可见' },
    ],
  },
  {
    entryKey: 'data.retention',
    displayName: '数据留存期限',
    options: [
      { value: 'D30', label: '30 天' },
      { value: 'D90', label: '90 天' },
      { value: 'D180', label: '180 天' },
      { value: 'D365', label: '365 天' },
    ],
  },
  {
    entryKey: 'member.data_export',
    displayName: '成员数据导出',
    options: [
      { value: 'ALLOWED', label: '允许' },
      { value: 'APPROVAL_REQUIRED', label: '需审批' },
      { value: 'FORBIDDEN', label: '禁止' },
    ],
  },
]

/** 策略值 → 中文标签（未收录值原样返回，不臆造翻译）。 */
export function policyValueLabel(entryKey: string, value: string | null): string {
  if (value === null) return ''
  const entry = POLICY_CATALOG.find((item) => item.entryKey === entryKey)
  return entry?.options.find((option) => option.value === value)?.label ?? value
}

/** 策略键 → 显示名（未收录键原样返回）。 */
export function policyDisplayName(entryKey: string): string {
  return POLICY_CATALOG.find((item) => item.entryKey === entryKey)?.displayName ?? entryKey
}

// ==== 留痕动作与结果（`space_action_log`） ====

export const ACTION_LOG_ACTION_LABELS: Record<string, string> = {
  CREATE: '创建空间',
  ENABLE: '启用空间',
  FREEZE: '冻结空间',
  UNFREEZE: '恢复空间',
  DISSOLVE: '解散空间',
  UPDATE: '修改配置',
  ADMIT_REQUEST: '提交加入申请',
  ADMIT_INVITE: '邀请成员',
  ADMIT_CONFIRM: '确认准入单',
  ADMIT_APPROVE: '审批通过',
  ADMIT_REJECT: '审批拒绝',
  LEAVE: '退出空间',
  REMOVE: '移除成员',
  ROLE_GRANT: '授予管理员',
  ROLE_REVOKE: '收回管理员',
  POLICY_OVERRIDE: '提交策略覆盖',
  POLICY_OVERRIDE_REJECTED: '策略覆盖被拒',
  POLICY_DEFINE: '平台策略变更',
  ACCESS_DENIED: '无权访问被拒',
}

export const ACTION_RESULT_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  DENIED: '被拒绝',
}

export const ACTION_RESULT_TYPES: Record<string, string> = {
  SUCCESS: 'success',
  DENIED: 'danger',
}

/** 留痕动作 → 中文标签（未收录动作原样返回）。 */
export function actionLogLabel(action: string): string {
  return ACTION_LOG_ACTION_LABELS[action] ?? action
}

// ==== 统一提示常量（页面一律引用，禁止散写） ====

/** 非成员同形口径：空间不存在（`1006C0004`）与无权访问（`1006C0007`）共用同一条文案与样式。 */
export const SPACE_NOT_ACCESSIBLE_TIP = '空间不存在或无权访问'

/** 触发同形提示的后端错误码（详情不可读的两条分支共用同一提示）。 */
export const SPACE_NOT_ACCESSIBLE_CODES: string[] = ['1006C0004', '1006C0007']

/** 演示身份主体编号为空时的前端前置拦截提示（零请求）。 */
export const DEMO_SUBJECT_REQUIRED_TIP = '请先填写演示身份主体编号'

/** 必填前置拦截提示（零请求）。 */
export const SPACE_NAME_REQUIRED_TIP = '请填写空间名称'
export const INVITE_SUBJECT_REQUIRED_TIP = '请填写被邀主体编号'
export const REMOVAL_REASON_REQUIRED_TIP = '请填写移除理由'
export const REJECT_REASON_REQUIRED_TIP = '请填写拒绝理由'
export const POLICY_KEY_REQUIRED_TIP = '请选择策略条目'
export const POLICY_VALUE_REQUIRED_TIP = '请选择策略值'

/** 解散二次确认文案（含"解散后不可恢复"与空间名称；取消 = 零请求）。 */
export function spaceDissolveConfirmTip(spaceName: string): string {
  return `解散后不可恢复，确认解散空间「${spaceName}」？`
}

/** 状态门槛的体验层提示（§8 第 6 条 / §6.3 C；文案按状态标签拼装，不散写）。 */
export function spaceActionBlockTip(status: string): string {
  return `${SPACE_STATUS_LABELS[status] ?? status}，无法执行该操作`
}

export function spacePolicyBlockTip(status: string): string {
  return `${SPACE_STATUS_LABELS[status] ?? status}，无法调整策略`
}

// ==== 空态文案（一律空态展示，不作报错） ====

export const SPACE_LIST_EMPTY_TIP = '暂无可查看的空间（公开空间或你参与的空间）'
export const MEMBERS_EMPTY_TIP = '暂无成员'
export const ADMISSIONS_EMPTY_TIP = '暂无准入单'
export const EFFECTIVE_POLICIES_EMPTY_TIP = '暂无有效策略'
export const ACTION_LOGS_EMPTY_TIP = '暂无操作留痕'
export const MY_ADMISSIONS_EMPTY_TIP = '暂无邀请或申请'
export const PLATFORM_POLICIES_EMPTY_TIP = '暂无平台策略条目'
