import { describe, it, expect, beforeEach } from 'vitest'
import { routes } from '../router/index'
import { hasPermission, getDemoRole, setDemoRole } from '../stores/demoRole'

/**
 * WBS-2.4.9 H3/H4 测试：
 * - 路由表结构断言：每条菜单路由必含 meta.title 与菜单属性；权限路由必含 meta.permission；
 * - 权限点守卫逻辑：admin 放行，普通用户拒绝；无权限点要求恒放行；
 * - 守卫 beforeEach 实际行为：普通用户访问受保护路由 → 重定向 dashboard + denied 提示参数；admin 放行。
 */

describe('路由表结构（H3）', () => {
  const root = routes.find((r) => r.path === '/')
  const children = root && 'children' in root ? (root.children ?? []) : []

  it('根路由为布局路由，业务页均为其子路由', () => {
    expect(root).toBeDefined()
    expect(children.length).toBeGreaterThanOrEqual(5)
  })

  it('每条菜单路由必含 meta.title', () => {
    const menuRoutes = children.filter((r) => r.meta?.menu === true)
    expect(menuRoutes.length).toBeGreaterThanOrEqual(4)
    for (const r of menuRoutes) {
      expect(r.meta?.title).toBeTruthy()
    }
  })

  it('权限演示路由声明了权限点，其余菜单路由未声明', () => {
    const adminRoute = children.find((r) => r.name === 'admin-only')
    expect(adminRoute?.meta?.permission).toBe('demo:admin')
    const normalRoutes = children.filter((r) => r.meta?.menu === true && r.name !== 'admin-only')
    for (const r of normalRoutes) {
      expect(r.meta?.permission).toBeUndefined()
    }
  })

  it('存在 404 兜底路由', () => {
    const notFound = children.find((r) => r.name === 'not-found')
    expect(notFound).toBeDefined()
    expect(notFound?.meta?.title).toBe('页面不存在')
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
})
