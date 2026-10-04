import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import DetailView from './DetailView.vue'
import {
  applyAdmission,
  approveAdmission,
  assignRole,
  getSpace,
  inviteMember,
  leaveSpace,
  listAdmissions,
  listMembers,
  removeMember,
  transferOwnership,
  type AdmissionItem,
  type MemberItem,
  type SpaceDetail,
} from '../../api/space'
import { setDemoSubject } from '../../api/client'
import { ApiError } from '../../api/client'
import { ADMISSIONS_EMPTY_TIP, AUTH_FAILED_CODE, MEMBERS_EMPTY_TIP, SPACE_STATUS_LABELS } from '../../constants/space'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'

/**
 * 空间详情·成员与准入区测试（WBS-3.2.6 hifi §6.3 Tab 2 + §7 T14~T22；评审 R1/R2/R3/R6 补齐）：
 * 成员表格 / 空态 / 分页传参与刷新 / 分页越界原样展示 /
 * 状态门槛提示（未启用·已冻结·已解散：邀请与申请按钮禁用并提示，R1）/
 * 本人行不得自我提权（R2）/ 邀请 / 申请 / 审批（拒绝理由必填）/ 角色授予与收回 /
 * 移除（理由必填）/ 退出（所有者保护文案如实展示）/ 所有权转移 / 认证失效引导回登录页。
 *
 * 挂载容器说明（评审 S5 加注）：成员与准入区是详情页的 Tab 2，**无独立组件文件**，
 * 本文件挂载 `DetailView.vue` 后聚焦该区行为（与 `DetailView.spec.ts` 的概览 / 策略面互补）。
 */
vi.mock('../../api/space', () => ({
  getSpace: vi.fn(),
  listActionLogs: vi.fn(),
  listMembers: vi.fn(),
  listAdmissions: vi.fn(),
  getEffectivePolicies: vi.fn(),
  enableSpace: vi.fn(),
  freezeSpace: vi.fn(),
  unfreezeSpace: vi.fn(),
  dissolveSpace: vi.fn(),
  updateSpace: vi.fn(),
  submitPolicyOverride: vi.fn(),
  inviteMember: vi.fn(),
  applyAdmission: vi.fn(),
  approveAdmission: vi.fn(),
  removeMember: vi.fn(),
  assignRole: vi.fn(),
  leaveSpace: vi.fn(),
  transferOwnership: vi.fn(),
}))

const mockedGetSpace = vi.mocked(getSpace)
const mockedMembers = vi.mocked(listMembers)
const mockedAdmissions = vi.mocked(listAdmissions)
const mockedInvite = vi.mocked(inviteMember)
const mockedApply = vi.mocked(applyAdmission)
const mockedApprove = vi.mocked(approveAdmission)
const mockedAssignRole = vi.mocked(assignRole)
const mockedRemove = vi.mocked(removeMember)
const mockedLeave = vi.mocked(leaveSpace)
const mockedTransfer = vi.mocked(transferOwnership)

const OTHER_SUBJECT = 'S20260925000002'
const OWNER_SUBJECT = 'S20260925000001'
let currentRouter: Router

function detail(over: Partial<SpaceDetail> = {}): SpaceDetail {
  return {
    id: 1,
    name: '城市交通数据空间',
    sceneType: 'FINTECH',
    accessMode: 'APPROVAL',
    visibility: 'PUBLIC',
    intro: '演示空间',
    status: 'ACTIVE',
    effectiveFrom: null,
    effectiveTo: null,
    ownerSubjectNo: OWNER_SUBJECT,
    createdAt: '2026-09-01T10:00:00',
    updatedAt: '2026-09-02T10:00:00',
    members: [{ subjectNo: OWNER_SUBJECT, role: 'OWNER' }],
    ...over,
  }
}

function member(over: Partial<MemberItem> = {}): MemberItem {
  return {
    id: 31,
    spaceId: 1,
    subjectNo: OWNER_SUBJECT,
    role: 'OWNER',
    status: 'ACTIVE',
    joinedAt: '2026-09-01T09:00:00',
    exitedAt: null,
    ...over,
  }
}

function admission(over: Partial<AdmissionItem> = {}): AdmissionItem {
  return {
    id: 41,
    spaceId: 1,
    subjectNo: 'S20260925000055',
    type: 'APPLICATION',
    status: 'PENDING_APPROVAL',
    operator: 'S20260925000055',
    reason: null,
    memberId: null,
    createdAt: '2026-09-05T10:00:00',
    ...over,
  }
}

function page<T>(list: T[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/spaces', name: 'space-list', component: { template: '<div />' } },
      { path: '/spaces/:id', name: 'space-detail', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  currentRouter = router
  await router.push('/spaces/1')
  await router.isReady()
  const wrapper = mount(DetailView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  for (const fn of [mockedGetSpace, mockedMembers, mockedAdmissions, mockedInvite, mockedApply,
    mockedApprove, mockedAssignRole, mockedRemove, mockedLeave, mockedTransfer]) {
    fn.mockReset()
  }
  setDemoSubject(OWNER_SUBJECT)
  mockedGetSpace.mockResolvedValue(detail())
  mockedMembers.mockResolvedValue(page([member()]))
  mockedAdmissions.mockResolvedValue(page([]))
})

describe('成员表格（T14）', () => {
  // 显式放宽超时：成员表格区块全量挂载在全量并行负载下可越 5s 默认门槛，非被测行为慢
  it('渲染角色 / 状态 / 加入时间，并按角色给出对应动作', async () => {
    mockedMembers.mockResolvedValue(page([
      member({ id: 31, subjectNo: OWNER_SUBJECT, role: 'OWNER' }),
      member({ id: 32, subjectNo: 'S20260925000002', role: 'ADMIN', joinedAt: '2026-09-02T09:00:00' }),
      member({ id: 33, subjectNo: 'S20260925000003', role: 'MEMBER', status: 'LEFT', joinedAt: '2026-09-03T09:00:00' }),
    ]))
    const wrapper = await mountPage()
    const text = wrapper.text()
    expect(text).toContain('所有者')
    expect(text).toContain('管理员')
    expect(text).toContain('成员')
    expect(text).toContain('生效中')
    expect(text).toContain('已退出')
    expect(text).toContain('2026-09-03T09:00:00')
    // OWNER 行无移除入口；管理员行可收回；成员行可授予
    expect(wrapper.findAll('.member-remove').length).toBe(2)
    expect(wrapper.findAll('.role-revoke').length).toBe(1)
    expect(wrapper.findAll('.role-grant').length).toBe(1)
  }, 15000)
})

describe('状态门槛提示（R1 / §8.6、§6.3 C）', () => {
  // 显式放宽超时：三档状态对照流程（逐档重挂载）在全量并行负载下可越 5s 默认门槛，非被测行为慢
  it('空间未启用 / 已冻结 / 已解散：邀请按钮禁用并给出状态门槛提示（T15 状态维度）', async () => {
    const blocked = [
      ['CREATED', SPACE_STATUS_LABELS.CREATED],
      ['FROZEN', SPACE_STATUS_LABELS.FROZEN],
      ['DISSOLVED', SPACE_STATUS_LABELS.DISSOLVED],
    ] as const
    for (const [status, label] of blocked) {
      document.body.innerHTML = ''
      mockedGetSpace.mockResolvedValue(detail({ accessMode: 'INVITE', status }))
      const wrapper = await mountPage()
      const invite = wrapper.find('.invite-open')
      expect(invite.exists()).toBe(true)
      expect(invite.attributes('disabled')).toBeDefined()
      expect(wrapper.find('.action-block-tip').text()).toContain(label)
      expect(wrapper.find('.action-block-tip').text()).toContain('无法执行该操作')
      await invite.trigger('click')
      await flushPromises()
      expect(mockedInvite).not.toHaveBeenCalled()
      wrapper.unmount()
    }
  }, 15000)

  it('审批制空间未启用：提交加入申请按钮禁用并给出状态门槛提示（T16 状态维度）', async () => {
    mockedGetSpace.mockResolvedValue(detail({ accessMode: 'APPROVAL', status: 'CREATED' }))
    const wrapper = await mountPage()
    const apply = wrapper.find('.apply-btn')
    expect(apply.attributes('disabled')).toBeDefined()
    expect(wrapper.find('.action-block-tip').text()).toContain(SPACE_STATUS_LABELS.CREATED)
    await apply.trigger('click')
    await flushPromises()
    expect(mockedApply).not.toHaveBeenCalled()
  })

  it('已启用空间：邀请按钮可用、无状态门槛提示，点击可打开邀请弹窗（双向）', async () => {
    mockedGetSpace.mockResolvedValue(detail({ accessMode: 'INVITE', status: 'ACTIVE' }))
    const wrapper = await mountPage()
    expect(wrapper.find('.invite-open').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('.action-block-tip').exists()).toBe(false)

    await wrapper.find('.invite-open').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('被邀主体编号')
  })
})

describe('本人行不得自我提权（R2 / §6.7 I）', () => {
  it('本人行（成员）无"授予管理员"入口，他人行保留', async () => {
    mockedMembers.mockResolvedValue(page([
      member({ id: 31, subjectNo: OWNER_SUBJECT, role: 'MEMBER' }),
      member({ id: 32, subjectNo: OTHER_SUBJECT, role: 'MEMBER' }),
    ]))
    const wrapper = await mountPage()
    expect(wrapper.findAll('.role-grant').length).toBe(1)
    // 移除入口不受 R2 影响（仅角色变更面禁用）
    expect(wrapper.findAll('.member-remove').length).toBe(2)
  })

  it('本人行（管理员）无"收回管理员"入口，他人行保留', async () => {
    mockedMembers.mockResolvedValue(page([
      member({ id: 31, subjectNo: OWNER_SUBJECT, role: 'ADMIN' }),
      member({ id: 32, subjectNo: OTHER_SUBJECT, role: 'ADMIN' }),
    ]))
    const wrapper = await mountPage()
    expect(wrapper.findAll('.role-revoke').length).toBe(1)
  })
})

describe('空态与分页（R6 / §7 T5、§8.3、§8.7）', () => {
  it('成员表为空时展示统一空态文案（不是报错）', async () => {
    mockedMembers.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain(MEMBERS_EMPTY_TIP)
  })

  it('准入单为空时展示统一空态文案（不是报错）', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain(ADMISSIONS_EMPTY_TIP)
  })

  it('成员分页：翻页按新页码与每页条数重新拉取（T14 分页）', async () => {
    mockedMembers.mockResolvedValue({ list: [member()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()
    expect(mockedMembers).toHaveBeenCalledWith(1, 1, 10)

    mockedMembers.mockResolvedValue({ list: [member({ id: 39, subjectNo: 'S20260925000088' })], total: 25, pageNum: 2, pageSize: 10, totalPages: 3 })
    await wrapper.find('.members-pager .btn-next').trigger('click')
    await flushPromises()
    expect(mockedMembers).toHaveBeenLastCalledWith(1, 2, 10)
    expect(wrapper.text()).toContain('S20260925000088')
  })

  it('准入单分页：翻页按新页码与每页条数重新拉取（T17 分页）', async () => {
    mockedAdmissions.mockResolvedValue({ list: [admission()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()

    mockedAdmissions.mockResolvedValue({ list: [admission({ id: 42, operator: 'S20260925000077' })], total: 25, pageNum: 2, pageSize: 10, totalPages: 3 })
    await wrapper.find('.admissions-pager .btn-next').trigger('click')
    await flushPromises()
    expect(mockedAdmissions).toHaveBeenLastCalledWith(1, 2, 10, undefined)
    expect(wrapper.text()).toContain('S20260925000077')
  })

  it('成员分页越界（1000C0001）：文案原样展示且表格保持原数据（不乐观更新）（§8.7）', async () => {
    mockedMembers.mockResolvedValue({ list: [member({ subjectNo: 'S20260925000066' })], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()
    mockedMembers.mockRejectedValue(new ApiError('1000C0001', '分页参数超出范围'))
    await wrapper.find('.members-pager .btn-next').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('分页参数超出范围')
    expect(wrapper.text()).toContain('S20260925000066')
  })

  it('准入单分页越界（1000C0001）：文案原样展示且表格保持原数据（不乐观更新）（§8.7）', async () => {
    mockedAdmissions.mockResolvedValue({ list: [admission({ operator: 'S20260925000055' })], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()
    mockedAdmissions.mockRejectedValue(new ApiError('1000C0001', '分页参数超出范围'))
    await wrapper.find('.admissions-pager .btn-next').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('分页参数超出范围')
    expect(wrapper.text()).toContain('S20260925000055')
  })

  it('认证失败或身份已失效（1000C0002）：原样提示 + 清演示登录态 + 引导回登录页（R3）', async () => {
    signInDemo()
    mockedMembers.mockRejectedValue(new ApiError(AUTH_FAILED_CODE, '认证失败或身份已失效'))
    await mountPage()
    await flushPromises()

    expect(document.body.textContent).toContain('认证失败或身份已失效')
    expect(isDemoAuthed()).toBe(false)
    expect(currentRouter.currentRoute.value.path).toBe('/login')
  })
})

describe('邀请与申请（T15/T16）', () => {
  it('邀请制空间：提交被邀主体编号，提示待确认且未成为成员前不出现成员行（T15）', async () => {
    mockedGetSpace.mockResolvedValue(detail({ accessMode: 'INVITE' }))
    mockedInvite.mockResolvedValue({ alreadyMember: false, admission: admission({ type: 'INVITATION', status: 'PENDING_CONFIRMATION' }), member: null })
    const wrapper = await mountPage()
    expect(wrapper.find('.apply-btn').exists()).toBe(false)
    await wrapper.find('.invite-open').trigger('click')
    await wrapper.find('.invite-subject input').setValue('S20260925000077')
    await wrapper.find('.invite-submit').trigger('click')
    await flushPromises()

    expect(mockedInvite).toHaveBeenCalledWith(1, { subjectNo: 'S20260925000077' })
    expect(document.body.textContent).toContain('待被邀方确认')
    expect(mockedMembers).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('S20260925000077')
  })

  it('被邀主体编号留空：前置拦截（零请求）（T15）', async () => {
    mockedGetSpace.mockResolvedValue(detail({ accessMode: 'INVITE' }))
    const wrapper = await mountPage()
    await wrapper.find('.invite-open').trigger('click')
    await wrapper.find('.invite-submit').trigger('click')
    await flushPromises()
    expect(mockedInvite).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请填写被邀主体编号')
  })

  it('公开 / 审批制空间：提交加入申请后进入待审批，不直接成为成员（T16）', async () => {
    mockedApply.mockResolvedValue({ alreadyMember: false, admission: admission(), member: null })
    const wrapper = await mountPage()
    expect(wrapper.find('.invite-open').exists()).toBe(false)
    await wrapper.find('.apply-btn').trigger('click')
    await flushPromises()
    expect(mockedApply).toHaveBeenCalledWith(1)
    expect(document.body.textContent).toContain('待审批')
    expect(mockedMembers).toHaveBeenCalledTimes(2)
  })
})

describe('审批（T17）', () => {
  it('拒绝理由留空：前置拦截（零请求）', async () => {
    mockedAdmissions.mockResolvedValue(page([admission()]))
    const wrapper = await mountPage()
    await wrapper.find('.approval-open').trigger('click')
    await wrapper.findComponent('.approval-decision').setValue('REJECT')
    await wrapper.find('.approval-submit').trigger('click')
    await flushPromises()
    expect(mockedApprove).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请填写拒绝理由')
  })

  it('填写拒绝理由：按 {decision: REJECT, reason} 提交并刷新', async () => {
    mockedAdmissions.mockResolvedValue(page([admission()]))
    mockedApprove.mockResolvedValue(admission({ status: 'REJECTED' }))
    const wrapper = await mountPage()
    await wrapper.find('.approval-open').trigger('click')
    await wrapper.findComponent('.approval-decision').setValue('REJECT')
    await wrapper.find('.approval-reason input').setValue('材料不全')
    await wrapper.find('.approval-submit').trigger('click')
    await flushPromises()
    expect(mockedApprove).toHaveBeenCalledWith(1, 41, { decision: 'REJECT', reason: '材料不全' })
    expect(mockedAdmissions).toHaveBeenCalledTimes(2)
  })

  it('通过申请：按 {decision: APPROVE} 提交（理由可不填）', async () => {
    mockedAdmissions.mockResolvedValue(page([admission()]))
    mockedApprove.mockResolvedValue(admission({ status: 'APPROVED' }))
    const wrapper = await mountPage()
    await wrapper.find('.approval-open').trigger('click')
    await wrapper.find('.approval-submit').trigger('click')
    await flushPromises()
    expect(mockedApprove).toHaveBeenCalledWith(1, 41, { decision: 'APPROVE' })
  })
})

describe('角色变更 / 移除 / 退出 / 转移（T19~T22）', () => {
  it('授予管理员：二次确认后按行级 memberId 调用端点并刷新（T19）', async () => {
    mockedMembers.mockResolvedValue(page([member({ id: 32, subjectNo: 'S20260925000002', role: 'MEMBER' })]))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mockedAssignRole.mockResolvedValue(member({ id: 32, role: 'ADMIN' }))
    const wrapper = await mountPage()
    await wrapper.find('.role-grant').trigger('click')
    await flushPromises()
    expect(mockedAssignRole).toHaveBeenCalledWith(1, 32, 'ADMIN')
    expect(mockedMembers).toHaveBeenCalledTimes(2)
  })

  it('收回管理员：二次确认取消 → 零请求（T19；目标为他人行，本人行见 R2 用例）', async () => {
    mockedMembers.mockResolvedValue(page([member({ id: 32, subjectNo: OTHER_SUBJECT, role: 'ADMIN' })]))
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue(new Error('cancel'))
    const wrapper = await mountPage()
    await wrapper.find('.role-revoke').trigger('click')
    await flushPromises()
    expect(mockedAssignRole).not.toHaveBeenCalled()
  })

  it('移除成员：理由留空 → 前置拦截（零请求）；填写 → 按行级 id 调用（T20）', async () => {
    mockedMembers.mockResolvedValue(page([member({ id: 33, subjectNo: 'S20260925000003', role: 'MEMBER' })]))
    mockedRemove.mockResolvedValue(member({ id: 33, status: 'REMOVED' }))
    const wrapper = await mountPage()
    await wrapper.find('.member-remove').trigger('click')
    await wrapper.find('.removal-submit').trigger('click')
    await flushPromises()
    expect(mockedRemove).not.toHaveBeenCalled()

    await wrapper.find('.removal-reason input').setValue('长期未参与')
    await wrapper.find('.removal-submit').trigger('click')
    await flushPromises()
    expect(mockedRemove).toHaveBeenCalledWith(1, 33, '长期未参与')
  })

  it('退出空间被所有者保护拒绝（1006C0009）：如实展示后端文案（T21）', async () => {
    mockedLeave.mockRejectedValue(new ApiError('1006C0009', '空间所有者受唯一所有者保护，不可执行该操作'))
    const wrapper = await mountPage()
    await wrapper.find('.leave-btn').trigger('click')
    await flushPromises()
    expect(mockedLeave).toHaveBeenCalledWith(1)
    expect(document.body.textContent).toContain('空间所有者受唯一所有者保护，不可执行该操作')
  })

  it('转移所有权：以成员行 id 为目标，二次确认后提交（T22）', async () => {
    mockedMembers.mockResolvedValue(page([
      member({ id: 31, subjectNo: OWNER_SUBJECT, role: 'OWNER' }),
      member({ id: 34, subjectNo: 'S20260925000004', role: 'MEMBER' }),
    ]))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mockedTransfer.mockResolvedValue({
      spaceOwnerSubjectNo: 'S20260925000004',
      formerOwner: member({ id: 31, role: 'MEMBER' }),
      newOwner: member({ id: 34, subjectNo: 'S20260925000004', role: 'OWNER' }),
      transferredAt: '2026-09-10T10:00:00',
    })
    const wrapper = await mountPage()
    // 仅所有者本人可见转移入口，且不对 OWNER 行自身显示
    const transferButtons = wrapper.findAll('.ownership-transfer')
    expect(transferButtons.length).toBe(1)
    await transferButtons[0].trigger('click')
    await flushPromises()
    expect(mockedTransfer).toHaveBeenCalledWith(1, 34)
  })
})
