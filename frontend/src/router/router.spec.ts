import { describe, it, expect, beforeEach } from 'vitest'
import type { Router } from 'vue-router'
import { routes } from '../router/index'
import { hasPermission, getDemoRole, setDemoRole } from '../stores/demoRole'
import { isDemoAuthed, signInDemo, signOutDemo } from '../stores/demoAuth'
// 卡 2（WBS-3.1.6b / DB-02）：顶层静态预热 QueueView——/review 懒加载组件（router/index.ts）的
// vite-node 首次转换在并行负载下可达数秒，原在 F4 导航内首转造成"第一次红、第二次绿"偶发。
// 顶层静态导入先于一切用例与超时窗口完成转换（先例：views/review/QueueView.spec.ts 的静态导入），
// 彻底把该环境成本移出被测导航；vitest 配置受限重试方案未采用（无必要，留痕）
import '../views/review/QueueView.vue'

/**
 * WBS-2.4.9 H3/H4 测试：
 * - 路由表结构断言：每条菜单路由必含 meta.title 与菜单属性；权限路由必含 meta.permission；
 * - 权限点守卫逻辑：admin 放行，普通用户拒绝；无权限点要求恒放行；
 * - 守卫 beforeEach 实际行为：普通用户访问受保护路由 → 重定向 dashboard + denied 提示参数；admin 放行。
 * WBS-2.4.12 增量：/login 顶层路由结构（B4）+ 登录守卫前置行为（B5/B7/边界 B-2）。
 */

describe('路由表结构（H3）', () => {
  const root = routes.find((r) => r.path === '/')
  const children = root && 'children' in root ? (root.children ?? []) : []

  it('根路由为布局路由，业务页均为其子路由', () => {
    expect(root).toBeDefined()
    expect(children.length).toBeGreaterThanOrEqual(5)
  })

  it('每条菜单路由必含 meta.title 与 meta.icon', () => {
    const menuRoutes = children.filter((r) => r.meta?.menu === true)
    expect(menuRoutes.length).toBeGreaterThanOrEqual(4)
    for (const r of menuRoutes) {
      expect(r.meta?.title).toBeTruthy()
      expect(r.meta?.icon).toBeTruthy()
    }
  })

  it('权限路由声明权限点，其余菜单路由未声明（WBS-3.1.5 扩 admin-only/review-queue；WBS-3.1.11 扩 did-management）', () => {
    const adminRoute = children.find((r) => r.name === 'admin-only')
    expect(adminRoute?.meta?.permission).toBe('demo:admin')
    const reviewRoute = children.find((r) => r.name === 'review-queue')
    expect(reviewRoute?.meta?.permission).toBe('subject.review')
    const didRoute = children.find((r) => r.name === 'did-management')
    expect(didRoute?.meta?.permission).toBe('did.admin')
    const permissionRouteNames = ['admin-only', 'review-queue', 'did-management']
    const normalRoutes = children.filter(
      (r) => r.meta?.menu === true && !permissionRouteNames.includes(String(r.name)),
    )
    for (const r of normalRoutes) {
      expect(r.meta?.permission).toBeUndefined()
    }
  })

  it('DID 管理路由（WBS-3.1.11）：menuOrder=8 追加菜单末尾、icon=Key、did.admin 权限；演示页不占菜单', () => {
    // 菜单末尾追加（lofi Q1/Q8：不重排既有菜单），menuOrder 全站最大值 7→8
    const menuOrders = children.filter((r) => r.meta?.menu === true).map((r) => r.meta?.menuOrder ?? 0)
    expect(Math.max(...menuOrders)).toBe(8)
    const didRoute = children.find((r) => r.name === 'did-management')
    expect(didRoute?.path).toBe('did')
    expect(didRoute?.meta?.title).toBe('DID 管理')
    expect(didRoute?.meta?.menu).toBe(true)
    expect(didRoute?.meta?.menuOrder).toBe(8)
    expect(didRoute?.meta?.icon).toBe('Key')
    expect(didRoute?.meta?.permission).toBe('did.admin')
    // 演示与验证页由 /did 页内入口进入，不占菜单（hifi §6.1）
    const demoRoute = children.find((r) => r.name === 'did-demo')
    expect(demoRoute?.path).toBe('did/demo')
    expect(demoRoute?.meta?.title).toBe('DID 演示与验证')
    expect(demoRoute?.meta?.permission).toBe('did.admin')
    expect(demoRoute?.meta?.menu).toBeUndefined()
  })

  it('存在 404 兜底路由', () => {
    const notFound = children.find((r) => r.name === 'not-found')
    expect(notFound).toBeDefined()
    expect(notFound?.meta?.title).toBe('页面不存在')
  })

  it('入驻进度查询路由（CHG-C-1.1-V1.2 行为 8-5）：menuOrder=7 追加菜单末尾、icon=Search、登录即可见无权限点', () => {
    const progressRoute = children.find((r) => r.name === 'subject-progress')
    expect(progressRoute?.path).toBe('subject/progress')
    expect(progressRoute?.meta?.title).toBe('入驻进度查询')
    expect(progressRoute?.meta?.menu).toBe(true)
    expect(progressRoute?.meta?.menuOrder).toBe(7)
    expect(progressRoute?.meta?.icon).toBe('Search')
    expect(progressRoute?.meta?.permission).toBeUndefined()
    // menuOrder=7 为入驻进度查询占位；WBS-3.1.11 新增 DID 管理（menuOrder=8）后全站最大值为 8
    const menuOrders = children.filter((r) => r.meta?.menu === true).map((r) => r.meta?.menuOrder ?? 0)
    expect(menuOrders).toContain(7)
    expect(Math.max(...menuOrders)).toBe(8)
  })

  it('登录页为顶层路由（WBS-2.4.12 B4）：不套布局、无菜单标记', () => {
    const login = routes.find((r) => r.name === 'login')
    expect(login).toBeDefined()
    expect(login?.path).toBe('/login')
    expect(login?.meta?.title).toBe('登录')
    expect('menu' in (login?.meta ?? {})).toBe(false)
  })
})

describe('权限点判断（H4）', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('无权限点要求恒放行', () => {
    expect(hasPermission(undefined)).toBe(true)
  })

  it('默认角色（普通用户）无 admin 权限点', () => {
    expect(getDemoRole()).toBe('user')
    expect(hasPermission('demo:admin')).toBe(false)
  })

  it('admin 角色放行 admin 权限点', () => {
    setDemoRole('admin')
    expect(getDemoRole()).toBe('admin')
    expect(hasPermission('demo:admin')).toBe(true)
  })
})

describe('守卫 beforeEach 实际行为（H4）', () => {
  beforeEach(() => {
    localStorage.clear()
    // WBS-2.4.12 守卫前置后，权限守卫用例需先置登录态（否则先被登录守卫拦截）
    signInDemo()
  })

  it('普通用户访问受保护路由：重定向 dashboard 并带 denied 提示参数', async () => {
    const { default: router } = await import('../router/index')
    setDemoRole('user')
    await router.push('/admin-only')
    expect(router.currentRoute.value.name).toBe('dashboard')
    expect(router.currentRoute.value.query.denied).toBe('1')
  })

  it('admin 角色访问受保护路由：放行', async () => {
    const { default: router } = await import('../router/index')
    setDemoRole('admin')
    await router.push('/admin-only')
    expect(router.currentRoute.value.name).toBe('admin-only')
  })

  it('未知路径：真实导航落到 404 兜底页', async () => {
    const { default: router } = await import('../router/index')
    await router.push('/no-such-page')
    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('普通用户直连 /review 清单路由：守卫拦截重定向工作台带 denied（WBS-3.1.6 F4，菜单显隐之外的链路级锚点）', async () => {
    const { default: router } = await import('../router/index')
    setDemoRole('user')
    await router.push('/review')
    expect(router.currentRoute.value.name).toBe('dashboard')
    expect(router.currentRoute.value.query.denied).toBe('1')
  })

  it('普通用户直连 /did：守卫拦截重定向工作台带 denied（WBS-3.1.11 E25）', async () => {
    const { default: router } = await import('../router/index')
    setDemoRole('user')
    await router.push('/did')
    expect(router.currentRoute.value.name).toBe('dashboard')
    expect(router.currentRoute.value.query.denied).toBe('1')
  })

  // 显式放宽超时：懒加载 QueueView 模块首次经 vite-node 转换（并行负载下可达数秒），非被测行为慢
  it('admin（兼审核员）直连 /review 放行（F4 正向对照）', async () => {
    const { default: router } = await import('../router/index')
    // QueueView 已在文件顶层静态预热（见文件头注释），此导航不再承担首次转换成本
    setDemoRole('admin')
    await router.push('/review')
    expect(router.currentRoute.value.name).toBe('review-queue')
  }, 30000)
})

describe('登录守卫（WBS-2.4.12 B5/B7/边界 B-2）', () => {
  let router: Router

  beforeEach(async () => {
    localStorage.clear()
    // 已知异址起点：vue-router 对同址重复导航会去重（守卫不触发），用例断言前统一回到 /dashboard
    ;({ default: router } = await import('../router/index'))
    signInDemo()
    await router.push('/dashboard')
  })

  it('未登录访问业务页：重定向 /login（B5）', async () => {
    signOutDemo()
    await router.push('/catalog')
    expect(router.currentRoute.value.name).toBe('login')
    expect(isDemoAuthed()).toBe(false)
  })

  it('登录态值为非 \'1\'（如 \'0\'）：一律视为未登录（边界 B-1，评审④P2-1 补）', async () => {
    localStorage.setItem('ctds-demo-auth', '0')
    await router.push('/catalog')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('未登录访问未知路径：先被登录守卫拦截而非落到 404（B7）', async () => {
    signOutDemo()
    await router.push('/no-such-page')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('已登录访问 /login：重定向工作台，不留双入口（边界 B-2）', async () => {
    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  it('退出后守卫再次拦截（B3/B5 联动）', async () => {
    signOutDemo()
    await router.push('/catalog')
    expect(router.currentRoute.value.name).toBe('login')
    signInDemo()
    await router.push('/catalog')
    expect(router.currentRoute.value.name).toBe('catalog')
  })
})
