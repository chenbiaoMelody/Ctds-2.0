import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import RbacView from './RbacView.vue'
import {
  grantPermission,
  listRbacChanges,
  listRbacRoles,
  revokePermission,
  type RbacChangeView,
  type RbacRoleView,
} from '../../api/platform'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode, setAdminPersona } from '../../stores/demoIdentity'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'

/**
 * 角色与权限页测试（WBS-3.9.5 hifi §8 T1~T5；lofi 屏 C；C-10.1 S1-1/S1-4/S1-5/S1-6/S1-10/S1-12/S1-13）：
 * - T1 清单 tab：GET roles 逐角色渲染，三员在前（仅前端展示序，不做数据加工）；
 * - T2 留痕 tab：分页八列 + beforeValue/afterValue 原文 + 翻页参数；
 * - T3 授予/收回：三元组传参 + 变更单号空值前端拦截（零请求）+ 成功回显变更号（E-2）；
 * - T4/T5 视角显隐矩阵（P7 实测：roles 仅 sysadmin / grant·revoke 仅 secadmin / changes 仅 audadmin）
 *   ——表单与 tab 一律渲染、提交/拉取后 403 原文如实呈现（前端不预判拦截以外加规则，B3-AC1）。
 */
vi.mock('../../api/platform', () => ({
  grantPermission: vi.fn(),
  listRbacChanges: vi.fn(),
  listRbacRoles: vi.fn(),
  revokePermission: vi.fn(),
}))

const mockedRoles = vi.mocked(listRbacRoles)
const mockedGrant = vi.mocked(grantPermission)
const mockedRevoke = vi.mocked(revokePermission)
const mockedChanges = vi.mocked(listRbacChanges)

function role(over: Partial<RbacRoleView> = {}): RbacRoleView {
  return { role: 'user', permissions: ['greeting.read', 'demo.read'], ...over }
}

function change(over: Partial<RbacChangeView> = {}): RbacChangeView {
  return {
    changeNo: 'RC7d724785ce1b4dbf',
    changeOrderNo: 'CHG-W395-PRECHECK-001',
    changedBy: 'S-admin-platform',
    changedAt: '2026-11-15 11:31:38.396',
    roleCode: 'user',
    permissionPoint: 'metering.read',
    beforeValue: '0',
    afterValue: '1',
    ...over,
  }
}

function page<T>(list: T[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/platform/rbac', name: 'platform-rbac', component: RbacView }],
  })
  await router.push('/platform/rbac')
  await router.isReady()
  const wrapper = mount(RbacView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((b) => b.text().includes(text))
}

async function activateTab(wrapper: ReturnType<typeof mount>, label: string) {
  const tabs = wrapper.findAll('.el-tabs__item')
  await tabs.find((t) => t.text().includes(label))?.trigger('click')
  await flushPromises()
}

/** 表单填写（沿 ConnectorGovernanceView.spec findComponent+setValue 先例：直接 emit update:modelValue）。 */
async function fillGrantForm(wrapper: ReturnType<typeof mount>, role: string, permission: string, orderNo: string | null) {
  await wrapper.findComponent('.rbac-role-select').setValue(role)
  await wrapper.findComponent('.rbac-permission-select').setValue(permission)
  if (orderNo) {
    await wrapper.find('input[placeholder*="CHG"]').setValue(orderNo)
  }
  await flushPromises()
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  mockedRoles.mockReset()
  mockedGrant.mockReset()
  mockedRevoke.mockReset()
  mockedChanges.mockReset()
  setDemoSubject('S-admin-platform')
  setActorMode('operator')
  setAdminPersona('sysadmin')
  if (!isDemoAuthed()) {
    signInDemo()
  }
  // 清单默认 stub：业务角色在前（断言展示序三员在前、演示档 platform.operator 不进三员段）；
  // 三员 + 治理档 + 演示档 + 业务角色
  mockedRoles.mockResolvedValue([
    role(),
    role({ role: 'platform.sysadmin', permissions: ['connector.health.read', 'platform.monitor.read', 'platform.alert.handle'] }),
    role({ role: 'platform.secadmin', permissions: ['connector.review', 'connector.upgrade.confirm', 'connector.cancel'] }),
    role({ role: 'platform.audadmin', permissions: ['evidence.governance'] }),
    role({ role: 'platform.operator', permissions: ['space.admin'] }),
    role({ role: 'requester', permissions: ['delivery.request'] }),
  ])
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('角色与权限页（T1 权限清单 tab）', () => {
  it('挂载即拉 roles；逐角色渲染权限点标签；三员在前、演示档随后（仅前端展示序，真机走查偏差回归）', async () => {
    const { wrapper } = await mountPage()
    expect(mockedRoles).toHaveBeenCalledTimes(1)
    const rows = wrapper.findAll('.el-table__row')
    expect(rows.length).toBe(6)
    // 三员在前：首行为 platform.sysadmin（stub 里业务角色 user 在前，展示序重排不加工数据）
    expect(rows[0]?.text()).toContain('platform.sysadmin')
    expect(rows[1]?.text()).toContain('platform.secadmin')
    expect(rows[2]?.text()).toContain('platform.audadmin')
    // 演示档 platform.operator 不进三员段（真机走查偏差修正回归，2026-11-15）
    expect(rows[3]?.text()).not.toContain('platform.operator')
    expect(rows[4]?.text()).toContain('platform.operator')
    // 权限点逐个渲染（等宽标签）
    expect(rows[0]?.text()).toContain('connector.health.read')
    expect(rows[0]?.text()).toContain('platform.monitor.read')
    expect(rows[2]?.text()).toContain('evidence.governance')
  })

  it('越权 403：非系统管理员视角拉清单 → ApiError 原文 el-alert（B3-AC1 文案统一）', async () => {
    setAdminPersona('secadmin')
    mockedRoles.mockRejectedValue(new ApiError('1000C0005', '无权限执行该操作'))
    const { wrapper } = await mountPage()
    expect(document.body.textContent).toContain('无权限执行该操作')
    expect(document.body.textContent).not.toContain('greeting.read')
    // 重试入口存在
    expect(button(wrapper, '重试')).toBeDefined()
  })
})

describe('角色与权限页（T3 授予与收回 tab）', () => {
  it('表单三元组提交：授予成功回显变更号（E-2）+ 提示留痕可查', async () => {
    mockedGrant.mockResolvedValue('RC7d724785ce1b4dbf')
    const { wrapper } = await mountPage()
    await activateTab(wrapper, '授予')
    await fillGrantForm(wrapper, 'user', 'metering.read', 'CHG-W395-T3-001')
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedGrant).toHaveBeenCalledWith({ role: 'user', permission: 'metering.read', changeOrderNo: 'CHG-W395-T3-001' })
    expect(document.body.textContent).toContain('RC7d724785ce1b4dbf')
    expect(document.body.textContent).toContain('留痕')
  })

  it('变更单号空值：前端拦截零请求（hifi §3 changeOrderNo 必填校验）', async () => {
    const { wrapper } = await mountPage()
    await activateTab(wrapper, '授予')
    await fillGrantForm(wrapper, 'user', 'metering.read', null)
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedGrant).not.toHaveBeenCalled()
    expect(mockedRevoke).not.toHaveBeenCalled()
  })

  it('动作切收回：提交走 revokePermission（动作单选区分，hifi §1.1）', async () => {
    mockedRevoke.mockResolvedValue('RC8a6768e4a19e4f6c')
    const { wrapper } = await mountPage()
    await activateTab(wrapper, '授予')
    const radios = wrapper.findAll('.el-radio')
    await radios.find((r) => r.text().includes('收回'))?.trigger('click')
    await flushPromises()
    await fillGrantForm(wrapper, 'user', 'metering.read', 'CHG-W395-T3-002')
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedRevoke).toHaveBeenCalledWith({ role: 'user', permission: 'metering.read', changeOrderNo: 'CHG-W395-T3-002' })
    expect(mockedGrant).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('RC8a6768e4a19e4f6c')
  })

  it('系统管理员视角提交授予：后端 403 原文如实呈现（表单仍渲染，前端不预判拦截，S1-4）', async () => {
    setAdminPersona('sysadmin')
    mockedGrant.mockRejectedValue(new ApiError('1000C0005', '无权限执行该操作'))
    const { wrapper } = await mountPage()
    await activateTab(wrapper, '授予')
    await fillGrantForm(wrapper, 'user', 'metering.read', 'CHG-W395-T5-NEG')
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedGrant).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('无权限执行该操作')
  })
})

describe('角色与权限页（T2/T5 变更留痕 tab）', () => {
  it('留痕 tab 懒加载：激活才拉 changes；八列渲染（beforeValue/afterValue 原文）', async () => {
    mockedChanges.mockResolvedValue(page([change()]))
    const { wrapper } = await mountPage()
    expect(mockedChanges).not.toHaveBeenCalled()
    await activateTab(wrapper, '留痕')
    expect(mockedChanges).toHaveBeenCalledWith(1, 10)
    const text = document.body.textContent ?? ''
    // 八列：变更号 / 变更单号 / 变更人 / 时点 / 角色 / 权限点 / 变更前 / 变更后
    expect(text).toContain('RC7d724785ce1b4dbf')
    expect(text).toContain('CHG-W395-PRECHECK-001')
    expect(text).toContain('S-admin-platform')
    expect(text).toContain('2026-11-15 11:31:38.396')
    expect(text).toContain('metering.read')
    // 前后值 cell 级断言（评审④ P3：整页 contain('0')/('1') 与时间戳/变更号重叠近恒真）
    const changeRow = [...document.body.querySelectorAll('.el-table__row')].find((r) =>
      r.textContent?.includes('RC7d724785ce1b4dbf'),
    )
    const cells = changeRow ? Array.from(changeRow.querySelectorAll('td')) : []
    expect(cells[cells.length - 2]?.textContent?.trim()).toBe('0')
    expect(cells[cells.length - 1]?.textContent?.trim()).toBe('1')
  })

  it('留痕翻页：点击下一页以 pageNum=2 重查', async () => {
    mockedChanges.mockResolvedValue(page([change()], 11))
    const { wrapper } = await mountPage()
    await activateTab(wrapper, '留痕')
    mockedChanges.mockClear()
    await wrapper.find('.el-pagination .btn-next').trigger('click')
    await flushPromises()
    expect(mockedChanges).toHaveBeenCalledWith(2, 10)
  })

  it('审计管理员视角留痕成功（P7 实测 changes 仅 audadmin）；系统管理员视角留痕 403 原文', async () => {
    // audadmin 视角：留痕成功
    setAdminPersona('audadmin')
    mockedChanges.mockResolvedValue(page([change()]))
    const { wrapper } = await mountPage()
    await activateTab(wrapper, '留痕')
    expect(mockedChanges).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('RC7d724785ce1b4dbf')
    // sysadmin 视角：留痕 403 原文
    setAdminPersona('sysadmin')
    mockedChanges.mockRejectedValue(new ApiError('1000C0005', '无权限执行该操作'))
    const { wrapper: sysWrapper } = await mountPage()
    await activateTab(sysWrapper, '留痕')
    expect(document.body.textContent).toContain('无权限执行该操作')
  })
})

describe('角色与权限页（T5 矩阵补格，评审④ P3）', () => {
  it('安全管理员视角授予成功（S1-5 界面动线：回显变更号）', async () => {
    setAdminPersona('secadmin')
    mockedGrant.mockResolvedValue('RC-S1-5-CASE')
    const { wrapper } = await mountPage()
    await activateTab(wrapper, '授予')
    await fillGrantForm(wrapper, 'user', 'metering.read', 'CHG-W395-T5-SEC')
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedGrant).toHaveBeenCalledWith({ role: 'user', permission: 'metering.read', changeOrderNo: 'CHG-W395-T5-SEC' })
    expect(document.body.textContent).toContain('RC-S1-5-CASE')
  })

  it('审计管理员视角拉清单 403 原文（P7 实测矩阵：roles 仅 sysadmin）', async () => {
    setAdminPersona('audadmin')
    mockedRoles.mockRejectedValue(new ApiError('1000C0005', '无权限执行该操作'))
    await mountPage()
    expect(document.body.textContent).toContain('无权限执行该操作')
  })
})

describe('角色与权限页（C-4 视角提示）', () => {
  it('页顶灰字提示当前三员视角 + 越权如实呈现口径', async () => {
    await mountPage()
    expect(document.body.textContent).toContain('系统管理员')
    expect(document.body.textContent).toContain('视角')
  })
})

describe('角色与权限页（WBS-3.9.6 T7 主体号空值拦截独立回归）', () => {
  /** 3.9.5 复审缺口三：角色清单空值分支文案独立断言（既有"变更单号空值零请求"主链路断言不动）。
   *  注：getDemoSubject 对空串回退默认主体（client.ts DEFAULT_SUBJECT），守卫分支真实可达输入
   *  = 纯空白主体（trim 判定），以实现实测为准。 */
  it('演示主体号为纯空白：拦截拉清单（零请求）+ 提示"请先填写演示身份主体编号"', async () => {
    setDemoSubject('   ')
    await mountPage()
    expect(mockedRoles).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请先填写演示身份主体编号')
  })
})
