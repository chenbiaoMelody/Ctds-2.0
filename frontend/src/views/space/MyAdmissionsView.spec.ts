import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import MyAdmissionsView from './MyAdmissionsView.vue'
import { confirmAdmission, listMyAdmissions, type AdmissionItem } from '../../api/space'
import { MY_ADMISSIONS_EMPTY_TIP } from '../../constants/space'

/**
 * 我的邀请与申请页测试（WBS-3.2.6 hifi §6.4 + §7 T18）：
 * 列表渲染 / 接受（CONFIRM）/ 谢绝（DECLINE，理由可选）/ 非待确认行无操作。
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

async function mountPage() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
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
