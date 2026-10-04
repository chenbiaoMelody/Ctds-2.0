import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import DetailView from './DetailView.vue'
import {
  dissolveSpace,
  enableSpace,
  freezeSpace,
  getEffectivePolicies,
  getSpace,
  listActionLogs,
  listAdmissions,
  listMembers,
  submitPolicyOverride,
  updateSpace,
  type EffectivePolicyItem,
  type SpaceDetail,
} from '../../api/space'
import { ApiError } from '../../api/client'
import {
  ACTION_LOGS_EMPTY_TIP,
  AUTH_FAILED_CODE,
  EFFECTIVE_POLICIES_EMPTY_TIP,
  SPACE_NOT_ACCESSIBLE_TIP,
  SPACE_STATUS_LABELS,
} from '../../constants/space'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'

/**
 * 空间详情页测试（WBS-3.2.6 hifi §6.3 + §7 T10~T13、T23~T25、T30b；评审 R3/R4/R6 补齐）：
 * 概览字段与三要素中文标签 / 生命周期按钮显隐与端点 / 解散两段式（取消 = 零请求）/
 * 配置变更白名单 / 策略来源三态与红线 / 覆盖提交值域与刷新 / 放宽被拒原样展示且不乐观更新 /
 * 同形提示 / 空态（留痕·策略）/ 留痕分页 / 认证失效引导回登录页。
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
const mockedLogs = vi.mocked(listActionLogs)
const mockedMembers = vi.mocked(listMembers)
const mockedAdmissions = vi.mocked(listAdmissions)
const mockedPolicies = vi.mocked(getEffectivePolicies)
const mockedEnable = vi.mocked(enableSpace)
const mockedFreeze = vi.mocked(freezeSpace)
const mockedDissolve = vi.mocked(dissolveSpace)
const mockedUpdate = vi.mocked(updateSpace)
const mockedOverride = vi.mocked(submitPolicyOverride)

const emptyPage = { list: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 }

function detail(over: Partial<SpaceDetail> = {}): SpaceDetail {
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
    ownerSubjectNo: 'S20260925000001',
    createdAt: '2026-09-01T10:00:00',
    updatedAt: '2026-09-02T10:00:00',
    members: [{ subjectNo: 'S20260925000001', role: 'OWNER' }],
    ...over,
  }
}

function policyItem(over: Partial<EffectivePolicyItem> = {}): EffectivePolicyItem {
  return {
    entryKey: 'data.retention',
    displayName: '数据留存期限',
    effectiveValue: 'D90',
    source: 'PLATFORM',
    provenance: 'INHERITED',
    note: '继承平台现状',
    platformValue: 'D90',
    spaceValue: null,
    spaceStatus: null,
    redline: true,
    ...over,
  }
}

let currentRouter: Router

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
  for (const fn of [mockedGetSpace, mockedLogs, mockedMembers, mockedAdmissions, mockedPolicies,
    mockedEnable, mockedFreeze, mockedDissolve, mockedUpdate, mockedOverride]) {
    fn.mockReset()
  }
  mockedGetSpace.mockResolvedValue(detail())
  mockedLogs.mockResolvedValue({
    list: [{
      id: 1, action: 'FREEZE', operator: 'S20260925000001', result: 'SUCCESS', reason: null,
      fromValue: 'ACTIVE', toValue: 'FROZEN', targetType: 'SPACE', targetId: '1',
      createdAt: '2026-09-03T10:00:00',
    }],
    total: 1, pageNum: 1, pageSize: 10, totalPages: 1,
  })
  mockedMembers.mockResolvedValue(emptyPage)
  mockedAdmissions.mockResolvedValue(emptyPage)
  mockedPolicies.mockResolvedValue([])
})

describe('概览（T10）', () => {
  // 显式放宽超时：空间详情页全量挂载 + 多区块渲染在全量并行负载下可越 5s 默认门槛，非被测行为慢
  it('渲染所有者主体编号 / 状态 / 创建时间与操作留痕四要素', async () => {
    const wrapper = await mountPage()
    const text = wrapper.text()
    expect(text).toContain('S20260925000001')
    expect(text).toContain(SPACE_STATUS_LABELS.ACTIVE)
    expect(text).toContain('2026-09-01T10:00:00')
    // 留痕：动作标签 + 结果 + 值变化（fromValue → toValue）
    expect(text).toContain('冻结空间')
    expect(text).toContain('ACTIVE → FROZEN')
  }, 15000)

  it('概览三要素（场景类型 / 参与方范围 / 可见性）显示中文标签，不出现裸枚举码（R4 / §6.6.1）', async () => {
    mockedGetSpace.mockResolvedValue(detail({ sceneType: 'MEDICAL', accessMode: 'APPROVAL', visibility: 'PRIVATE' }))
    const wrapper = await mountPage()
    const text = wrapper.text()
    expect(text).toContain('医疗验证')
    expect(text).toContain('审批制')
    expect(text).toContain('不公开')
    expect(text).not.toContain('MEDICAL')
    expect(text).not.toContain('APPROVAL')
    expect(text).not.toContain('PRIVATE')
  })
})

describe('空态与分页（R6 / §7 T5、§8.3、§8.7）', () => {
  it('操作留痕为空时展示统一空态文案（不是报错）', async () => {
    mockedLogs.mockResolvedValue(emptyPage)
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain(ACTION_LOGS_EMPTY_TIP)
  })

  it('有效策略为空时展示统一空态文案（不是报错）', async () => {
    mockedPolicies.mockResolvedValue([])
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain(EFFECTIVE_POLICIES_EMPTY_TIP)
  })

  it('操作留痕分页：翻页按新页码与每页条数重新拉取（T10 分页）', async () => {
    mockedLogs.mockResolvedValue({
      list: [{
        id: 1, action: 'FREEZE', operator: 'S20260925000001', result: 'SUCCESS', reason: null,
        fromValue: 'ACTIVE', toValue: 'FROZEN', targetType: 'SPACE', targetId: '1',
        createdAt: '2026-09-03T10:00:00',
      }],
      total: 25, pageNum: 1, pageSize: 10, totalPages: 3,
    })
    const wrapper = await mountPage()
    expect(mockedLogs).toHaveBeenCalledWith(1, 1, 10)

    mockedLogs.mockResolvedValue({
      list: [{
        id: 2, action: 'ENABLE', operator: 'S20260925000009', result: 'SUCCESS', reason: null,
        fromValue: 'CREATED', toValue: 'ACTIVE', targetType: 'SPACE', targetId: '1',
        createdAt: '2026-09-04T10:00:00',
      }],
      total: 25, pageNum: 2, pageSize: 10, totalPages: 3,
    })
    await wrapper.find('.logs-pager .btn-next').trigger('click')
    await flushPromises()
    expect(mockedLogs).toHaveBeenLastCalledWith(1, 2, 10)
    expect(wrapper.text()).toContain('S20260925000009')
  })

  it('操作留痕分页越界（1000C0001）：文案原样展示且表格保持原数据（不乐观更新）（§8.7）', async () => {
    mockedLogs.mockResolvedValue({
      list: [{
        id: 1, action: 'FREEZE', operator: 'S20260925000001', result: 'SUCCESS', reason: null,
        fromValue: 'ACTIVE', toValue: 'FROZEN', targetType: 'SPACE', targetId: '1',
        createdAt: '2026-09-03T10:00:00',
      }],
      total: 25, pageNum: 1, pageSize: 10, totalPages: 3,
    })
    const wrapper = await mountPage()
    mockedLogs.mockRejectedValue(new ApiError('1000C0001', '分页参数超出范围'))
    await wrapper.find('.logs-pager .btn-next').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('分页参数超出范围')
    expect(wrapper.text()).toContain('ACTIVE → FROZEN')
  })

  it('认证失败或身份已失效（1000C0002）：原样提示 + 清演示登录态 + 引导回登录页，且不落入同形提示（R3 / §8.9）', async () => {
    signInDemo()
    mockedGetSpace.mockRejectedValue(new ApiError(AUTH_FAILED_CODE, '认证失败或身份已失效'))
    const wrapper = await mountPage()
    await flushPromises()

    expect(document.body.textContent).toContain('认证失败或身份已失效')
    expect(isDemoAuthed()).toBe(false)
    expect(currentRouter.currentRoute.value.path).toBe('/login')
    // 认证失效不是"不存在 / 无权"：不得渲染同形提示
    expect(wrapper.find('.not-accessible-tip').exists()).toBe(false)
  })

  it('非认证类加载失败（1006S0001）：原样展示后端文案且不跳转（§8.8 反向面）', async () => {
    mockedGetSpace.mockRejectedValue(new ApiError('1006S0001', '主体服务暂不可用'))
    await mountPage()
    await flushPromises()

    expect(document.body.textContent).toContain('主体服务暂不可用')
    expect(currentRouter.currentRoute.value.path).toBe('/spaces/1')
  })
})

describe('生命周期（T11/T12）', () => {
  it('状态为已启用时只显示"冻结"与"解散"，点击冻结调用对应端点（T11）', async () => {
    mockedFreeze.mockResolvedValue(detail({ status: 'FROZEN' }))
    const wrapper = await mountPage()
    expect(wrapper.find('.lifecycle-freeze').exists()).toBe(true)
    expect(wrapper.find('.lifecycle-enable').exists()).toBe(false)
    expect(wrapper.find('.lifecycle-unfreeze').exists()).toBe(false)
    expect(wrapper.find('.lifecycle-dissolve').exists()).toBe(true)

    await wrapper.find('.lifecycle-freeze').trigger('click')
    await flushPromises()
    expect(mockedFreeze).toHaveBeenCalledWith(1)
  })

  it('状态为已创建时显示"启用"，点击启用调用对应端点（T11）', async () => {
    mockedGetSpace.mockResolvedValue(detail({ status: 'CREATED' }))
    mockedEnable.mockResolvedValue(detail({ status: 'ACTIVE' }))
    const wrapper = await mountPage()
    expect(wrapper.find('.lifecycle-enable').exists()).toBe(true)
    expect(wrapper.find('.lifecycle-freeze').exists()).toBe(false)

    await wrapper.find('.lifecycle-enable').trigger('click')
    await flushPromises()
    expect(mockedEnable).toHaveBeenCalledWith(1)
  })

  it('解散二次确认：取消 → 零请求（T12）', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue(new Error('cancel'))
    const wrapper = await mountPage()
    await wrapper.find('.lifecycle-dissolve').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalled()
    expect(String(confirm.mock.calls[0][0])).toContain('解散后不可恢复')
    expect(mockedDissolve).not.toHaveBeenCalled()
  })

  it('解散二次确认：确认 → 调用解散端点（请求体二次确认标记由 API 模块固定为 true）（T12）', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mockedDissolve.mockResolvedValue(detail({ status: 'DISSOLVED' }))
    const wrapper = await mountPage()
    await wrapper.find('.lifecycle-dissolve').trigger('click')
    await flushPromises()
    expect(mockedDissolve).toHaveBeenCalledWith(1)
  })
})

describe('配置变更（T13）', () => {
  it('请求体只含白名单字段（intro / effectiveFrom / effectiveTo），多余字段零出现', async () => {
    // 生效期为空：请求体只应出现被修改的 intro（空字段不提交、非白名单字段零出现）
    mockedGetSpace.mockResolvedValue(detail({ effectiveFrom: null, effectiveTo: null }))
    mockedUpdate.mockResolvedValue(detail({ intro: '新简介' }))
    const wrapper = await mountPage()
    await wrapper.find('.config-open').trigger('click')
    await wrapper.find('.config-intro textarea').setValue('新简介')
    await wrapper.find('.config-submit').trigger('click')
    await flushPromises()

    expect(mockedUpdate).toHaveBeenCalledTimes(1)
    const payload = mockedUpdate.mock.calls[0][1] as Record<string, unknown>
    expect(Object.keys(payload)).toEqual(['intro'])
    expect(payload).not.toHaveProperty('name')
    expect(payload).not.toHaveProperty('status')
  })

  it('配置变更：生效期有值时按白名单原样提交（其余字段仍零出现）', async () => {
    mockedUpdate.mockResolvedValue(detail())
    const wrapper = await mountPage()
    await wrapper.find('.config-open').trigger('click')
    await wrapper.find('.config-submit').trigger('click')
    await flushPromises()

    const payload = mockedUpdate.mock.calls[0][1] as Record<string, unknown>
    expect(Object.keys(payload).sort()).toEqual(['effectiveFrom', 'effectiveTo', 'intro'])
  })
})

describe('策略区（T23~T25）', () => {
  it('有效策略表格渲染来源三态标注与红线 tag（T23）', async () => {
    mockedPolicies.mockResolvedValue([
      policyItem({ entryKey: 'data.visibility', displayName: '数据可见范围', provenance: 'INHERITED', redline: true }),
      policyItem({ entryKey: 'data.retention', displayName: '数据留存期限', provenance: 'SPACE_EFFECTIVE', source: 'SPACE', spaceValue: 'D180', redline: false }),
      policyItem({ entryKey: 'member.data_export', displayName: '成员数据导出', provenance: 'SPACE_NOT_EFFECTIVE_TAKE_STRICTER', source: 'SPACE', spaceValue: 'ALLOWED', redline: true }),
    ])
    const wrapper = await mountPage()
    const text = wrapper.text()
    expect(text).toContain('继承自平台')
    expect(text).toContain('空间级生效')
    expect(text).toContain('空间覆盖未生效（取严）')
    expect(wrapper.findAll('.redline-tag').length).toBe(2)
    // 生效值按目录常量译中文（不散写）
    expect(text).toContain('90 天')
  })

  it('覆盖提交：值域下拉来自目录常量，提交成功后刷新表格（T24）', async () => {
    mockedPolicies.mockResolvedValue([policyItem({ entryKey: 'member.data_export', displayName: '成员数据导出', provenance: 'INHERITED', redline: false })])
    mockedOverride.mockResolvedValue({
      entryKey: 'member.data_export', effectiveValue: 'FORBIDDEN', source: 'SPACE',
      provenance: 'SPACE_EFFECTIVE', note: '空间级覆盖生效', platformValue: 'ALLOWED', spaceValue: 'FORBIDDEN', redline: false,
    })
    const wrapper = await mountPage()
    // 值域来自目录常量：切换条目键 → 取值自动落入该键封闭值域的首项（data.retention → D30）
    await wrapper.findComponent('.override-key').setValue('data.retention')
    await flushPromises()
    await wrapper.find('.override-submit').trigger('click')
    await flushPromises()
    expect(mockedOverride).toHaveBeenLastCalledWith(1, { entryKey: 'data.retention', entryValue: 'D30' })

    await wrapper.findComponent('.override-key').setValue('member.data_export')
    await flushPromises()
    await wrapper.findComponent('.override-value').setValue('FORBIDDEN')
    await wrapper.find('.override-submit').trigger('click')
    await flushPromises()
    expect(mockedOverride).toHaveBeenCalledWith(1, { entryKey: 'member.data_export', entryValue: 'FORBIDDEN' })
    // 成功后刷新（挂载时 1 次 + 两次提交各 1 次）
    expect(mockedPolicies).toHaveBeenCalledTimes(3)
  })

  it('放宽被拒（1006C0013）：文案原样展示且表格不变（不得乐观更新）（T25）', async () => {
    mockedPolicies.mockResolvedValue([policyItem({ entryKey: 'data.retention', effectiveValue: 'D90', redline: true })])
    mockedOverride.mockRejectedValue(new ApiError('1006C0013', '该条目为平台级限制项，不可放宽'))
    const wrapper = await mountPage()
    await wrapper.find('.override-submit').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('该条目为平台级限制项，不可放宽')
    // 不乐观更新：策略表格仍为原值，且未再次拉取
    expect(mockedPolicies).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('90 天')
  })
})

describe('非成员同形提示（T30b）', () => {
  it('空间不存在（1006C0004）与无权（1006C0007）两条失败路径渲染同一条提示文案', async () => {
    for (const code of ['1006C0004', '1006C0007']) {
      document.body.innerHTML = ''
      mockedGetSpace.mockRejectedValue(new ApiError(code, '后端文案不参与同形口径'))
      const wrapper = await mountPage()
      const tip = wrapper.find('.not-accessible-tip')
      expect(tip.exists()).toBe(true)
      expect(tip.text()).toContain(SPACE_NOT_ACCESSIBLE_TIP)
      wrapper.unmount()
    }
  })

  it('公开摘要态（非成员可见）同样使用该提示常量（同一条文案与样式）', async () => {
    mockedGetSpace.mockResolvedValue({
      id: 1, name: '公开空间', sceneType: 'OTHER', accessMode: 'OPEN', visibility: 'PUBLIC',
      intro: null, status: 'ACTIVE', effectiveFrom: null, effectiveTo: null,
    })
    const wrapper = await mountPage()
    expect(wrapper.find('.not-accessible-tip').text()).toContain(SPACE_NOT_ACCESSIBLE_TIP)
  })
})
