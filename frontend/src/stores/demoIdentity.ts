/**
 * WBS-3.2.6 演示身份档（`docs/designs/WBS-3.2.6-hifi.md` §4 + lofi Q2-A）：
 * - 档位：`subject`（普通主体，默认）| `operator`（平台运营方），localStorage 键 `ctds-demo-actor-mode`；
 * - 角色头：普通档 = `applicant`；运营档 = `applicant,platform.operator`
 *   （服务端 `ctds.auth.permissions.platform.operator` 映射 space.admin/space.member/platform.policy）；
 * - 硬约束（测试锚点 T27）：**普通档角色头不得携带 `platform.operator`**——否则成员越权类剧本步骤
 *   会被服务端角色头映射放行，使"越权被拒"步骤失真（让绿灯失真）；
 * - 主体编号复用 `api/client.ts` 的 `getDemoSubject()` / `setDemoSubject()`，不新建第二份存储；
 * - 全局 `demoRolesHeader()` 一字不动（最小权限面，沿 WBS-3.1.11 Q7 先例）。
 * WBS-3.3.6 扩展（hifi §4）：目录域页面级角色头 `catalogRolesHeader()`——普通档 `provider` /
 * 运营档 `admin`（沿 3.3.5 验收走查实测口径）；硬约束（T17）：**普通档不得携带 `admin`**。
 * WBS-3.3.6 缺陷修复批（走查登记 F1，hifi §12 E3）：新增演示身份变更信号 `demoIdentityRevision`
 * ——保存身份且值确有变化时自增，顶栏所属布局以它为当前页面 key：身份变更 = 页面整体重挂并按
 * 新身份重拉数据（服务端行级过滤仍为判定源；刷新自愈的缺陷根因即组件态未随身份变化失效）。
 */
import { ref } from 'vue'

export type ActorMode = 'subject' | 'operator'

const ACTOR_MODE_KEY = 'ctds-demo-actor-mode'
const DEFAULT_ACTOR_MODE: ActorMode = 'subject'

/** 档位 → 中文标签（顶栏"演示身份"控件；页面源码不散写档位中文）。 */
export const ACTOR_MODE_LABELS: Record<ActorMode, string> = {
  subject: '普通主体',
  operator: '平台运营方',
}

/** 读取档位（存量非法值一律回落普通档）。 */
export function getActorMode(): ActorMode {
  return localStorage.getItem(ACTOR_MODE_KEY) === 'operator' ? 'operator' : DEFAULT_ACTOR_MODE
}

/** 写入档位。 */
export function setActorMode(mode: ActorMode): void {
  localStorage.setItem(ACTOR_MODE_KEY, mode)
}

/**
 * 演示身份变更信号（F1 修复批）：保存身份且值确有变化时自增。
 * 顶栏所属布局（MainLayout）以它作为 `router-view` 的 key —— 变更 = 当前页面整体重挂，
 * 按主体取数的列表 / 下拉 / 档位提示随之重拉。
 */
export const demoIdentityRevision = ref(0)

/** 标记演示身份已变更（唯一调用点 = 顶栏保存且主体或档位确有变化）。 */
export function markDemoIdentityChanged(): void {
  demoIdentityRevision.value += 1
}

/** 空间域页面级角色头（覆盖 `apiJson` 的全局演示角色头）。 */
export function spaceRolesHeader(): string {
  return getActorMode() === 'operator' ? 'applicant,platform.operator' : 'applicant'
}

/** 目录域页面级角色头（WBS-3.3.6；覆盖 `apiJson` 的全局演示角色头，与空间域互不污染）。 */
export function catalogRolesHeader(): string {
  return getActorMode() === 'operator' ? 'admin' : 'provider'
}
