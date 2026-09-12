import { expect, test, type Page } from '@playwright/test'

/**
 * WBS-2.4.12 登录冒烟示例（hifi §2 用例契约 E1–E5）：骨架期演示登录链路自动回归。
 * 定位纪律（ADR-011）：语义化 role/placeholder/text 定位，禁 CSS/XPath 选择器。
 * Playwright 每用例独立上下文（localStorage 全新），用例内按需预置登录态。
 */

const DEMO_AUTH_KEY = 'ctds-demo-auth'

/** 已登录态预置：先到任意页再写入 localStorage（E5 需登出后不被 init script 覆写） */
async function signInViaStorage(page: Page) {
  await page.goto('/login')
  await page.evaluate((key) => {
    localStorage.setItem(key, '1')
  }, DEMO_AUTH_KEY)
}

test.describe('登录冒烟（E1–E5）', () => {
  test('E1 未登录访问受保护页：重定向登录页，表单元素齐全', async ({ page }) => {
    await page.goto('/dashboard')
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByPlaceholder('请输入用户名')).toBeVisible()
    await expect(page.getByPlaceholder('请输入口令')).toBeVisible()
    await expect(page.getByRole('button', { name: /登\s*录/ })).toBeVisible()
    await expect(page.getByText('骨架期演示登录')).toBeVisible()
    // B7（评审①P2-2 补）：未登录访问未知路径同样先被登录守卫拦截，而非落到 404
    await page.goto('/no-such-page')
    await expect(page).toHaveURL(/\/login$/)
  })

  test('E2 口令留空提交：校验拦下，不写登录态', async ({ page }) => {
    await page.goto('/login')
    await page.getByPlaceholder('请输入用户名').fill('demo')
    await page.getByRole('button', { name: /登\s*录/ }).click()
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByText('请输入口令')).toBeVisible()
    const authed = await page.evaluate((key) => localStorage.getItem(key), DEMO_AUTH_KEY)
    expect(authed).toBeNull()
  })

  test('E3 演示登录成功：进入工作台，侧边栏菜单渲染', async ({ page }) => {
    await page.goto('/login')
    await page.getByPlaceholder('请输入用户名').fill('demo')
    await page.getByPlaceholder('请输入口令').fill('demo123')
    await page.getByRole('button', { name: /登\s*录/ }).click()
    await expect(page).toHaveURL(/\/dashboard$/)
    await expect(page.getByRole('heading', { name: '工作台' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '工作台' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '标准能力' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '数据目录' })).toBeVisible()
    // B1 直接断言（评审④P3-2 补）：登录成功即写入演示登录态
    const authed = await page.evaluate((key) => localStorage.getItem(key), DEMO_AUTH_KEY)
    expect(authed).toBe('1')
    // B-3（评审④P2-2 补）：登录态 localStorage 持久，刷新不丢
    await page.reload()
    await expect(page).toHaveURL(/\/dashboard$/)
  })

  test('E4 user 角色访问仅管理员页：权限守卫重定向工作台并提示', async ({ page }) => {
    await page.addInitScript((key) => {
      localStorage.setItem(key, '1')
      localStorage.setItem('ctds-demo-role', 'user')
    }, DEMO_AUTH_KEY)
    await page.goto('/admin-only')
    await expect(page).toHaveURL(/\/dashboard\?denied=1$/)
    await expect(page.getByText('无权限访问该页面')).toBeVisible()
  })

  test('E5 退出：回登录页，再访内页被守卫再次拦截', async ({ page }) => {
    await signInViaStorage(page)
    await page.goto('/dashboard')
    await page.getByRole('button', { name: '退出' }).click()
    await expect(page).toHaveURL(/\/login$/)
    await page.goto('/dashboard')
    await expect(page).toHaveURL(/\/login$/)
    // B-4（评审④P2-3 补）：退出后浏览器回退键同样被守卫再拦截（守卫在导航层，不依赖页面事件）
    await page.goBack()
    await expect(page).toHaveURL(/\/login$/)
  })
})
