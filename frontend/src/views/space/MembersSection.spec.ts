import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
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

/**
 * 空间详情·成员与准入区测试（WBS-3.2.6 hifi §6.3 Tab 2 + §7 T14~T22）：
 * 成员表格 / 邀请 / 申请 / 审批（拒绝理由必填）/ 角色授予与收回 / 移除（理由必填）/
 * 退出（所有者保护文案如实展示）/ 所有权转移。
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

const OWNER_SUBJECT = 'S20260925000001'

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
    ],
  })
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

  it('收回管理员：二次确认取消 → 零请求（T19）', async () => {
    mockedMembers.mockResolvedValue(page([member({ id: 32, role: 'ADMIN' })]))
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
