import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import ListView from './ListView.vue'
import { createSpace, listSpaces, type SpaceSummary } from '../../api/space'
import { ApiError } from '../../api/client'
import { AUTH_FAILED_CODE, SPACE_LIST_EMPTY_TIP } from '../../constants/space'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'

/**
 * 逻辑空间列表页测试（WBS-3.2.6 hifi §6.2 + §7 T5~T9；评审 R3/R6 补齐）：
 * 字段渲染 / 空态 / 分页传参与刷新 / 分页越界原样展示 / 检索传参与重置 /
 * 创建成功刷新 / 创建被拒文案原样展示 / 认证失效引导回登录页。
 */
vi.mock('../../api/space', () => ({
  listSpaces: vi.fn(),
  createSpace: vi.fn(),
}))

const mockedList = vi.mocked(listSpaces)
const mockedCreate = vi.mocked(createSpace)

function space(over: Partial<SpaceSummary> = {}): SpaceSummary {
  return {
    id: 1,
    name: '城市交通数据空间',
    sceneType: 'FINTECH',
    accessMode: 'OPEN',
    visibility: 'PUBLIC',
    intro: '演示空间',
    status: 'ACTIVE',
    effectiveFrom: '2026-01-01',
    effectiveTo: '2026-12-31',
    ...over,
  }
}

function page(list: SpaceSummary[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 10, totalPages: 1 }
}

let currentRouter: Router

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/spaces', name: 'space-list', component: { template: '<div />' } },
      { path: '/spaces/:id', name: 'space-detail', component: { template: '<div />' } },
      { path: '/spaces/my-admissions', name: 'space-my-admissions', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  currentRouter = router
  await router.push('/spaces')
  await router.isReady()
  const wrapper = mount(ListView, {
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
  mockedList.mockResolvedValue(page([space()]))
})

describe('列表渲染（T5）', () => {
  it('逐列渲染字段并做中文标签映射（场景类型 / 参与方范围 / 可见性 / 状态 / 生效期）', async () => {
    mockedList.mockResolvedValue(page([
      space({ id: 1, name: '城市交通数据空间', sceneType: 'FINTECH', accessMode: 'OPEN', status: 'ACTIVE' }),
      space({
        id: 2, name: '医疗隐私空间', sceneType: 'MEDICAL', accessMode: 'APPROVAL',
        visibility: 'PRIVATE', status: 'CREATED', effectiveFrom: null, effectiveTo: null,
      }),
    ]))
    const wrapper = await mountPage()
    const text = wrapper.text()
    for (const header of ['空间名称', '场景类型', '参与方范围', '可见性', '状态', '生效期', '操作']) {
      expect(text).toContain(header)
    }
    expect(text).toContain('城市交通数据空间')
    expect(text).toContain('普惠金融')
    expect(text).toContain('审批制')
    expect(text).toContain('不公开')
    expect(text).toContain('已创建（未启用）')
    expect(text).toContain('2026-01-01 ~ 2026-12-31')
  })

  it('无可见空间时展示空态文案（空态不是报错）', async () => {
    mockedList.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain(SPACE_LIST_EMPTY_TIP)
  })

  it('总页数 > 1 时显示分页控件，并按下发的总数 / 每页条数渲染（T5 分页）', async () => {
    mockedList.mockResolvedValue({ list: [space()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()
    expect(wrapper.find('.pager').exists()).toBe(true)
    expect(wrapper.findAll('.pager .el-pager li').length).toBe(3)
  })

  it('翻页：按新页码与每页条数重新拉取并刷新列表（T5 分页刷新）', async () => {
    mockedList.mockResolvedValue({ list: [space({ name: '第 1 页空间' })], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()
    expect(mockedList).toHaveBeenCalledWith(1, 10, undefined)

    mockedList.mockResolvedValue({ list: [space({ id: 11, name: '第 2 页空间' })], total: 25, pageNum: 2, pageSize: 10, totalPages: 3 })
    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()

    expect(mockedList).toHaveBeenLastCalledWith(2, 10, undefined)
    expect(mockedList).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('第 2 页空间')
  })

  it('分页越界（1000C0001）：文案原样展示，且列表保持原数据（不乐观更新）（§8.7）', async () => {
    mockedList.mockResolvedValue({ list: [space({ name: '第 1 页空间' })], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()
    mockedList.mockRejectedValue(new ApiError('1000C0001', '分页参数超出范围'))
    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('分页参数超出范围')
    expect(wrapper.text()).toContain('第 1 页空间')
    expect(mockedList).toHaveBeenCalledTimes(2)
  })

  it('加载失败（非认证类）：原样展示后端业务文案且不跳转（§8.8）', async () => {
    mockedList.mockRejectedValue(new ApiError('1006S0001', '主体服务暂不可用'))
    await mountPage()
    await flushPromises()
    expect(document.body.textContent).toContain('主体服务暂不可用')
    expect(currentRouter.currentRoute.value.path).toBe('/spaces')
  })

  it('认证失败或身份已失效（1000C0002）：原样提示 + 清演示登录态 + 引导回登录页（§8.9 / R3）', async () => {
    signInDemo()
    expect(isDemoAuthed()).toBe(true)
    mockedList.mockRejectedValue(new ApiError(AUTH_FAILED_CODE, '认证失败或身份已失效'))
    await mountPage()
    await flushPromises()

    expect(document.body.textContent).toContain('认证失败或身份已失效')
    expect(isDemoAuthed()).toBe(false)
    expect(currentRouter.currentRoute.value.path).toBe('/login')
  })
})

describe('检索与重置（T6）', () => {
  it('关键字检索按名称前缀传参（空值不过滤）', async () => {
    const wrapper = await mountPage()
    expect(mockedList).toHaveBeenCalledWith(1, 10, undefined)

    await wrapper.find('.keyword-input input').setValue('城市')
    await wrapper.find('.search-btn').trigger('click')
    await flushPromises()
    expect(mockedList).toHaveBeenLastCalledWith(1, 10, '城市')

    await wrapper.find('.reset-btn').trigger('click')
    await flushPromises()
    expect(mockedList).toHaveBeenLastCalledWith(1, 10, undefined)
  })
})

describe('创建空间（T7~T9）', () => {
  it('创建成功：提交契约字段并刷新列表（新空间状态 = 已创建（未启用））', async () => {
    mockedCreate.mockResolvedValue({} as never)
    const wrapper = await mountPage()
    await wrapper.find('.create-btn').trigger('click')
    await wrapper.find('.create-name input').setValue('新场景空间')
    await wrapper.find('.create-submit').trigger('click')
    await flushPromises()

    expect(mockedCreate).toHaveBeenCalledWith({
      name: '新场景空间',
      sceneType: 'FINTECH',
      accessMode: 'OPEN',
      visibility: 'PUBLIC',
    })
    expect(mockedList).toHaveBeenCalledTimes(2)

    mockedList.mockResolvedValue(page([space({ name: '新场景空间', status: 'CREATED' })]))
    await wrapper.find('.search-btn').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('新场景空间')
    expect(wrapper.text()).toContain('已创建（未启用）')
  })

  it('名称留空：前置拦截（零请求）', async () => {
    const wrapper = await mountPage()
    await wrapper.find('.create-btn').trigger('click')
    await wrapper.find('.create-submit').trigger('click')
    await flushPromises()
    expect(mockedCreate).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请填写空间名称')
  })

  it('主体未入驻被拒（1006C0001）：原样展示后端统一业务文案（T8）', async () => {
    mockedCreate.mockRejectedValue(new ApiError('1006C0001', '主体未入驻或不存在，无法执行该操作'))
    const wrapper = await mountPage()
    await wrapper.find('.create-btn').trigger('click')
    await wrapper.find('.create-name input').setValue('新场景空间')
    await wrapper.find('.create-submit').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('主体未入驻或不存在，无法执行该操作')
    // 失败不乐观更新：列表仍为原数据且不再刷新
    expect(mockedList).toHaveBeenCalledTimes(1)
  })

  it('重名被拒（1006C0003）：原样展示后端统一业务文案（T9）', async () => {
    mockedCreate.mockRejectedValue(new ApiError('1006C0003', '同一所有者已存在同名空间'))
    const wrapper = await mountPage()
    await wrapper.find('.create-btn').trigger('click')
    await wrapper.find('.create-name input').setValue('城市交通数据空间')
    await wrapper.find('.create-submit').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('同一所有者已存在同名空间')
  })
})
