import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import PlatformPolicyView from './PlatformPolicyView.vue'
import { createPlatformPolicy, listPlatformPolicies, updatePlatformPolicy, type PolicyEntry } from '../../api/space'
import { ApiError } from '../../api/client'
import { PLATFORM_POLICIES_EMPTY_TIP } from '../../constants/space'

/**
 * 平台策略治理页测试（WBS-3.2.6 hifi §6.5 + §7 T26）：
 * 列表渲染 / 新建（键限目录三键）/ 变更（键不可变更，值与红线至少一项）/ 错误原样展示。
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

async function mountPage() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
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
