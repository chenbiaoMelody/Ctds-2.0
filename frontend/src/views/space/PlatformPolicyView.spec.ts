import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import PlatformPolicyView from './PlatformPolicyView.vue'
import { createPlatformPolicy, listPlatformPolicies, updatePlatformPolicy, type PolicyEntry } from '../../api/space'
import { ApiError } from '../../api/client'
import { AUTH_FAILED_CODE, PLATFORM_POLICIES_EMPTY_TIP } from '../../constants/space'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'

/**
 * 平台策略治理页测试（WBS-3.2.6 hifi §6.5 + §7 T26；评审 R3/R6 补齐）：
 * 列表渲染 / 空态 / 分页传参与刷新 / 分页越界原样展示 /
 * 新建（键限目录三键）/ 变更（键不可变更，值与红线至少一项）/ 错误原样展示 /
 * 认证失效引导回登录页。
 */
vi.mock('../../api/space', () => ({
  listPlatformPolicies: vi.fn(),
  createPlatformPolicy: vi.fn(),
  updatePlatformPolicy: vi.fn(),
}))

const mockedList = vi.mocked(listPlatformPolicies)
const mockedCreate = vi.mocked(createPlatformPolicy)
const mockedUpdate = vi.mocked(updatePlatformPolicy)

function entry(over: Partial<PolicyEntry> = {}): PolicyEntry {
  return {
    id: 21,
    entryKey: 'data.retention',
    displayName: '数据留存期限',
    entryValue: 'D90',
    redline: true,
    status: 'ACTIVE',
    createdAt: '2026-09-01T10:00:00',
    updatedAt: '2026-09-02T10:00:00',
    ...over,
  }
}

function page(list: PolicyEntry[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 10, totalPages: 1 }
}

let currentRouter: Router

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  currentRouter = router
  const wrapper = mount(PlatformPolicyView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  mockedList.mockReset()
  mockedCreate.mockReset()
  mockedUpdate.mockReset()
  mockedList.mockResolvedValue(page([entry()]))
})

describe('平台策略条目列表（T26）', () => {
  it('逐列渲染条目键 / 显示名 / 值 / 红线 / 状态 / 时间，并提供变更入口', async () => {
    mockedList.mockResolvedValue(page([
      entry(),
      entry({ id: 22, entryKey: 'member.data_export', displayName: '成员数据导出', entryValue: 'ALLOWED', redline: false }),
    ]))
    const wrapper = await mountPage()
    const text = wrapper.text()
    expect(text).toContain('data.retention')
    expect(text).toContain('数据留存期限')
    expect(text).toContain('90 天')
    expect(text).toContain('允许')
    expect(text).toContain('2026-09-01T10:00:00')
    expect(wrapper.findAll('.redline-tag').length).toBe(1)
    expect(wrapper.findAll('.policy-edit-btn').length).toBe(2)
  })

  it('无条目时展示空态文案', async () => {
    mockedList.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain(PLATFORM_POLICIES_EMPTY_TIP)
  })

  it('加载失败：原样展示后端业务文案', async () => {
    mockedList.mockRejectedValue(new ApiError('1000C0005', '无权限执行该操作'))
    await mountPage()
    await flushPromises()
    expect(document.body.textContent).toContain('无权限执行该操作')
  })

  it('翻页：按新页码与每页条数重新拉取并刷新（T26 分页）', async () => {
    mockedList.mockResolvedValue({ list: [entry()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()
    expect(mockedList).toHaveBeenCalledWith(1, 10)

    mockedList.mockResolvedValue({ list: [entry({ id: 22, entryKey: 'member.data_export', displayName: '成员数据导出', entryValue: 'FORBIDDEN' })], total: 25, pageNum: 2, pageSize: 10, totalPages: 3 })
    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()

    expect(mockedList).toHaveBeenLastCalledWith(2, 10)
    expect(mockedList).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('禁止')
  })

  it('分页越界（1000C0001）：文案原样展示且列表保持原数据（不乐观更新）（§8.7）', async () => {
    mockedList.mockResolvedValue({ list: [entry()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()
    mockedList.mockRejectedValue(new ApiError('1000C0001', '分页参数超出范围'))
    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('分页参数超出范围')
    expect(wrapper.text()).toContain('90 天')
    expect(mockedList).toHaveBeenCalledTimes(2)
  })

  it('认证失败或身份已失效（1000C0002）：原样提示 + 清演示登录态 + 引导回登录页（§8.9 / R3）', async () => {
    signInDemo()
    mockedList.mockRejectedValue(new ApiError(AUTH_FAILED_CODE, '认证失败或身份已失效'))
    await mountPage()
    await flushPromises()

    expect(document.body.textContent).toContain('认证失败或身份已失效')
    expect(isDemoAuthed()).toBe(false)
    expect(currentRouter.currentRoute.value.path).toBe('/login')
  })

  it('非认证类加载失败（1006S0001）：原样展示后端文案且不跳转（§8.8 反向面）', async () => {
    mockedList.mockRejectedValue(new ApiError('1006S0001', '主体服务暂不可用'))
    await mountPage()
    await flushPromises()

    expect(document.body.textContent).toContain('主体服务暂不可用')
    expect(currentRouter.currentRoute.value.path).toBe('/')
  })
})

describe('新建条目（T26）', () => {
  it('提交目录三键与值域常量取值，成功后刷新列表', async () => {
    mockedCreate.mockResolvedValue(entry())
    const wrapper = await mountPage()
    await wrapper.find('.policy-create-open').trigger('click')
    await wrapper.find('.policy-create-submit').trigger('click')
    await flushPromises()
    // 默认 = 目录首键与其封闭值域首值（键与值均取自 constants/space.ts）
    expect(mockedCreate).toHaveBeenCalledWith({
      entryKey: 'data.visibility',
      entryValue: 'ALL_PLATFORM',
      redline: false,
    })
    expect(mockedList).toHaveBeenCalledTimes(2)
  })

  it('键/值不合目录要求被拒（1006C0012）：文案原样展示', async () => {
    mockedCreate.mockRejectedValue(new ApiError('1006C0012', '策略条目键或值不符合平台目录要求'))
    const wrapper = await mountPage()
    await wrapper.find('.policy-create-open').trigger('click')
    await wrapper.find('.policy-create-submit').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('策略条目键或值不符合平台目录要求')
  })
})

describe('变更条目（T26）', () => {
  it('键只读不可变更；变更值后按 {entryValue} 提交并刷新', async () => {
    mockedUpdate.mockResolvedValue(entry({ entryValue: 'D180' }))
    const wrapper = await mountPage()
    await wrapper.find('.policy-edit-btn').trigger('click')
    const keyInput = wrapper.find('.policy-edit-key input')
    expect((keyInput.element as HTMLInputElement).disabled).toBe(true)
    expect((keyInput.element as HTMLInputElement).value).toBe('data.retention')

    await wrapper.findComponent('.policy-edit-value').setValue('D180')
    await wrapper.find('.policy-edit-submit').trigger('click')
    await flushPromises()
    expect(mockedUpdate).toHaveBeenCalledWith(21, { entryValue: 'D180' })
    expect(mockedList).toHaveBeenCalledTimes(2)
  })

  it('值与红线均未变更：前置拦截（零请求）', async () => {
    const wrapper = await mountPage()
    await wrapper.find('.policy-edit-btn').trigger('click')
    await wrapper.find('.policy-edit-submit').trigger('click')
    await flushPromises()
    expect(mockedUpdate).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请至少变更值与红线中的一项')
  })
})
