/**
 * WBS-3.2.6 认证失效引导（hifi §8.9；评审 R3 修复落点）：
 * 空间域请求以 `1000C0002`（认证失败或身份已失效，`AUTH_FAILED_CODE`）失败时，
 * 界面**原样展示后端文案**并**引导回登录页**（不吞、不改、不伪造成功）。
 *
 * **撤登录态是"引导回登录页"的必要条件**：路由登录守卫要求"未登录"才允许停留在 `/login`
 * （`router/index.ts` 的 `beforeEach`：已登录访问 `/login` 会被弹回工作台），
 * 故仅 `push('/login')` 会被守卫立即弹回、等于空转；本模块在跳转前清演示登录态
 * （`signOutDemo`，只清登录态，演示角色与演示身份主体编号按既有口径保留）。
 */
import { ElMessage } from 'element-plus'
import type { Router } from 'vue-router'
import { ApiError } from '../../api/client'
import { AUTH_FAILED_CODE } from '../../constants/space'
import { signOutDemo } from '../../stores/demoAuth'

/**
 * 命中 `1000C0002` 时：原样提示后端文案 → 清演示登录态 → 回登录页；返回 true。
 * 调用方在返回 true 时**必须立即 return**，避免再叠加一条通用兜底提示。
 */
export function guideToLoginIfAuthFailed(error: unknown, router: Router): boolean {
  if (!(error instanceof ApiError) || error.code !== AUTH_FAILED_CODE) return false
  ElMessage.error(error.message)
  signOutDemo()
  void router.push('/login')
  return true
}
