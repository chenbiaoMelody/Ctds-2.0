/**
 * WBS-2.4.9 H4 演示角色占位（骨架期最小实现，非 Pinia）。
 * 真实授权数据源（角色/权限点）待 3.9.x 工作包接入；本模块仅用于演示
 * "路由驱动菜单 + 权限点守卫"机制，不臆造后端接口。
 */
export type DemoRole = 'user' | 'admin'

const STORAGE_KEY = 'ctds-demo-role'
const DEFAULT_ROLE: DemoRole = 'user'

export function getDemoRole(): DemoRole {
  const stored = localStorage.getItem(STORAGE_KEY)
  return stored === 'admin' ? 'admin' : DEFAULT_ROLE
}

export function setDemoRole(role: DemoRole): void {
  localStorage.setItem(STORAGE_KEY, role)
}

/**
 * 权限点判断（骨架期占位规则）：
 * - 无权限点要求（undefined）→ 放行；
 * - 要求权限点 → 仅 admin 角色放行（演示 admin 拥有全部权限点）。
 */
export function hasPermission(required: string | undefined): boolean {
  if (!required) return true
  return getDemoRole() === 'admin'
}
