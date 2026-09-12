/**
 * WBS-2.4.12 演示登录态占位（骨架期最小实现，非 Pinia，与 demoRole.ts 同模式）。
 * 真实认证/令牌签发归属待 3.9.1 规格澄清；本模块仅承载"进没进"的演示登录态，
 * 不臆造后端接口。演示角色切换（stores/demoRole）职责不混入。
 */
const STORAGE_KEY = 'ctds-demo-auth'
const AUTHED_VALUE = '1'

/** 仅 AUTHED_VALUE 视为已登录；缺失或其他值一律未登录（hifi 边界值 B-1） */
export function isDemoAuthed(): boolean {
  return localStorage.getItem(STORAGE_KEY) === AUTHED_VALUE
}

/** 登录成功写入演示登录态（hifi B1） */
export function signInDemo(): void {
  localStorage.setItem(STORAGE_KEY, AUTHED_VALUE)
}

/** 退出清除登录态；演示角色（ctds-demo-role）保留不清，职责不混（hifi B3） */
export function signOutDemo(): void {
  localStorage.removeItem(STORAGE_KEY)
}
