/**
 * 订单域常量单一源（WBS-3.7.3 hifi §4.6；C-7.1 规格业务文案）。
 * 状态 / 动作 / 角色标签与后端 1011 段文案同源直译，不在视图层二次包装。
 */

/** 订单状态三态中文标签（hifi §4.6）。 */
export const ORDER_STATUS_LABELS: Record<string, string> = {
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成（已订购）',
  CLOSED: '已关闭',
}

/** 订单状态 tag 类型（Element Plus tag type；进行中 primary / 已完成 success / 已关闭 info）。 */
export const ORDER_STATUS_TAG_TYPES: Record<string, string> = {
  IN_PROGRESS: 'primary',
  COMPLETED: 'success',
  CLOSED: 'info',
}

/** 订单留痕动作标签（hifi §4.6；时间线渲染源）。 */
export const ORDER_ACTION_LABELS: Record<string, string> = {
  CREATE: '创建订单',
  COMPLETE: '合约生效，订单完成',
  CLOSE: '合约终止，订单关闭',
}

/** 我的角色标签（B12-1 双角色读面）。 */
export const MY_ROLE_LABELS: Record<string, string> = {
  REQUESTER: '需方',
  PROVIDER: '供方',
}

/** 订单不存在 / 非本方同形提示（与后端 1011C0002 文案逐字一致——防枚举同形）。 */
export const ORDER_NOT_FOUND_TIP = '订单不存在或无权查看'
