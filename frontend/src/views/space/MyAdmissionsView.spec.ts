import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import MyAdmissionsView from './MyAdmissionsView.vue'
import { confirmAdmission, listMyAdmissions, type AdmissionItem } from '../../api/space'
import { ApiError } from '../../api/client'
import { AUTH_FAILED_CODE, MY_ADMISSIONS_EMPTY_TIP } from '../../constants/space'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'

/**
 * 我的邀请与申请页测试（WBS-3.2.6 hifi §6.4 + §7 T18；评审 R3/R6 补齐）：
 * 列表渲染 / 空态 / 分页传参与刷新 / 分页越界原样展示 /
 * 接受（CONFIRM）/ 谢绝（DECLINE，理由可选）/ 非待确认行无操作 / 认证失效引导回登录页。
 */
vi.mock('../../api/space', () => ({
  listMyAdmissions: vi.fn(),
  confirmAdmission: vi.fn(),
}))

const mockedList = vi.mocked(listMyAdmissions)
const mockedConfirm = vi.mocked(confirmAdmission)

function admission(over: Partial<AdmissionItem> = {}): AdmissionItem {
  return {
    id: 11,
    spaceId: 3,
    subjectNo: 'S20260925000001',
    type: 'INVITATION',
    status: 'PENDING_CONFIRMATION',
    operator: 'S20260925000009',
    reason: '业务协作',
    memberId: null,
    createdAt: '2026-09-20T10:00:00',
    ...over,
  }
}

function page(list: AdmissionItem[]) {
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
  const wrapper = mount(MyAdmissionsView, {
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
  mockedConfirm.mockReset()
  mockedList.mockResolvedValue(page([admission()]))
})

describe('我的邀请与申请（T18）', () => {
  it('渲染空间 id / 形态 / 状态 / 操作者 / 理由，并只对待确认行给出操作', async () => {
    mockedList.mockResolvedValue(page([
      admission({ id: 11, status: 'PENDING_CONFIRMATION' }),
      admission({ id: 12, spaceId: 4, type: 'APPLICATION', status: 'PENDING_APPROVAL', operator: 'S20260925000001' }),
    ]))
    const wrapper = await mountPage()
    const text = wrapper.text()
    expect(text).toContain('邀请')
    expect(text).toContain('申请')
    expect(text).toContain('待确认')
    expect(text).toContain('待审批')
    expect(text).toContain('S20260925000009')
    expect(text).toContain('业务协作')
    // 仅待确认行给出接受 / 谢绝
    expect(wrapper.findAll('.accept-btn').length).toBe(1)
    expect(wrapper.findAll('.decline-btn').length).toBe(1)
  })

  it('空态展示统一空态文案（不是报错）', async () => {
    mockedList.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain(MY_ADMISSIONS_EMPTY_TIP)
  })

  it('翻页：按新页码与每页条数重新拉取并刷新（T18 分页）', async () => {
    mockedList.mockResolvedValue({ list: [admission()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()
    expect(mockedList).toHaveBeenCalledWith(1, 10)

    mockedList.mockResolvedValue({ list: [admission({ id: 12, operator: 'S20260925000099' })], total: 25, pageNum: 2, pageSize: 10, totalPages: 3 })
    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()

    expect(mockedList).toHaveBeenLastCalledWith(2, 10)
    expect(mockedList).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('S20260925000099')
  })

  it('分页越界（1000C0001）：文案原样展示且列表保持原数据（不乐观更新）（§8.7）', async () => {
    mockedList.mockResolvedValue({ list: [admission()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()
    mockedList.mockRejectedValue(new ApiError('1000C0001', '分页参数超出范围'))
    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('分页参数超出范围')
    expect(wrapper.text()).toContain('业务协作')
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

  it('接受邀请：以本人身份提交 CONFIRM 并刷新列表', async () => {
    mockedConfirm.mockResolvedValue(admission({ status: 'APPROVED' }))
    const wrapper = await mountPage()
    await wrapper.find('.accept-btn').trigger('click')
    await flushPromises()
    expect(mockedConfirm).toHaveBeenCalledWith(3, 11, { decision: 'CONFIRM' })
    expect(mockedList).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('已加入空间')
  })

  it('谢绝邀请：可填理由，提交 DECLINE 并刷新列表', async () => {
    mockedConfirm.mockResolvedValue(admission({ status: 'DECLINED' }))
    const wrapper = await mountPage()
    await wrapper.find('.decline-btn').trigger('click')
    await wrapper.find('.decline-reason input').setValue('暂不参与')
    await wrapper.find('.decline-submit').trigger('click')
    await flushPromises()
    expect(mockedConfirm).toHaveBeenCalledWith(3, 11, { decision: 'DECLINE', reason: '暂不参与' })
    expect(mockedList).toHaveBeenCalledTimes(2)
  })

  it('谢绝理由留空：仍可提交（理由可选），请求体不含 reason', async () => {
    mockedConfirm.mockResolvedValue(admission({ status: 'DECLINED' }))
    const wrapper = await mountPage()
    await wrapper.find('.decline-btn').trigger('click')
    await wrapper.find('.decline-submit').trigger('click')
    await flushPromises()
    expect(mockedConfirm).toHaveBeenCalledWith(3, 11, { decision: 'DECLINE' })
  })
})
