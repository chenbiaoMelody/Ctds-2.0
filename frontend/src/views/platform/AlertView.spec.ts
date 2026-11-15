import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import AlertView from './AlertView.vue'
import { disposeAlert, listAlerts, type AlertItemView, type AlertsView } from '../../api/platform'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode, setAdminPersona } from '../../stores/demoIdentity'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'

/**
 * 告警与处置页测试（WBS-3.9.5 hifi §8 T11/T12/T13；lofi 屏 B；C-10.2 S2-1/S2-3~S2-7）：
 * - T11 七要素列 + stateNote 透传 + 封闭两规则固定入列（前端零过滤零增删）；
 * - T12 处置对话框：动作单选（确认/静默/升级）+ 静默时长（仅静默显示，缺省 30 / 上限 1440 控件约束）
 *   + 请求体断言（durationMinutes 仅 SILENCE）+ 回显卡（result=OK / silenceId / recordedAt / ESCALATE 待生效提示）；
 * - T13 firing→已触发〔红〕/ resolved→已恢复〔灰〕双态；恢复后条目仍可见（列表权威 = 读面）；
 * - 处置按钮仅系统管理员视角渲染（hifi §5 映射；S2-7 写面双向）。
 */
vi.mock('../../api/platform', () => ({
  disposeAlert: vi.fn(),
  getMetrics: vi.fn(),
  listAlerts: vi.fn(),
}))

const mockedAlerts = vi.mocked(listAlerts)
const mockedDispose = vi.mocked(disposeAlert)

function alert(over: Partial<AlertItemView> = {}): AlertItemView {
  return {
    alertName: 'CtdsBackendDown',
    severity: 'critical',
    target: 'evidence-service',
    triggeredAt: '2026-11-15T10:00:00',
    currentValue: '5.2',
    threshold: '5',
    state: 'firing',
    stateNote: null,
    ...over,
  }
}

function alertsView(list: AlertItemView[]): AlertsView {
  return { alerts: list }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/platform/alerts', name: 'platform-alerts', component: AlertView }],
  })
  await router.push('/platform/alerts')
  await router.isReady()
  const wrapper = mount(AlertView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((b) => b.text().includes(text))
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  mockedAlerts.mockReset()
  mockedDispose.mockReset()
  setDemoSubject('S-admin-platform')
  setActorMode('operator')
  setAdminPersona('sysadmin')
  if (!isDemoAuthed()) {
    signInDemo()
  }
  mockedAlerts.mockResolvedValue(alertsView([alert()]))
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('告警与处置页（T11 七要素列表）', () => {
  it('挂载即拉 alerts；七要素列渲染 + stateNote 灰字透传', async () => {
    mockedAlerts.mockResolvedValue(alertsView([
      alert({ stateNote: '连续 3 个周期未恢复' }),
    ]))
    const { wrapper } = await mountPage()
    expect(mockedAlerts).toHaveBeenCalledTimes(1)
    const text = document.body.textContent ?? ''
    // 七要素：告警名 / 级别 / 对象 / 触发时点 / 当前值 / 阈值 / 状态
    expect(text).toContain('CtdsBackendDown')
    expect(text).toContain('evidence-service')
    expect(text).toContain('严重')
    expect(text).toContain('2026-11-15T10:00:00')
    expect(text).toContain('5.2')
    expect(text).toContain('5')
    expect(text).toContain('已触发')
    expect(text).toContain('连续 3 个周期未恢复')
    expect(wrapper.findAll('.el-table__row').length).toBe(1)
  })

  it('封闭两规则固定入列：CtdsBackendDown 与 CtdsBackendHighErrorRate 同表可见（前端零过滤）', async () => {
    mockedAlerts.mockResolvedValue(alertsView([
      alert(),
      alert({ alertName: 'CtdsBackendHighErrorRate', severity: 'warning', state: 'resolved', currentValue: '5.2', threshold: '5' }),
    ]))
    const { wrapper } = await mountPage()
    const text = document.body.textContent ?? ''
    expect(text).toContain('CtdsBackendDown')
    expect(text).toContain('CtdsBackendHighErrorRate')
    expect(wrapper.findAll('.el-table__row').length).toBe(2)
  })

  it('错误态：数据源不可用 1013S0003 原文 el-alert + 重试（E-1 实测形态）', async () => {
    mockedAlerts.mockRejectedValue(new ApiError('1013S0003', '监控数据源暂不可用，请稍后重试'))
    const { wrapper } = await mountPage()
    expect(document.body.textContent).toContain('监控数据源暂不可用，请稍后重试')
    mockedAlerts.mockResolvedValue(alertsView([alert()]))
    await button(wrapper, '重试')?.trigger('click')
    await flushPromises()
    expect(mockedAlerts).toHaveBeenCalledTimes(2)
  })

  it('空态：暂无告警（不伪造数据）', async () => {
    mockedAlerts.mockResolvedValue(alertsView([]))
    await mountPage()
    expect(document.body.textContent).toContain('暂无')
  })
})

describe('告警与处置页（T13 双态呈现）', () => {
  it('firing→已触发〔红〕/ resolved→已恢复〔灰〕；恢复后条目仍可见（列表权威 = 读面）', async () => {
    mockedAlerts.mockResolvedValue(alertsView([
      alert(),
      alert({ alertName: 'CtdsBackendHighErrorRate', state: 'resolved', severity: 'warning' }),
    ]))
    const { wrapper } = await mountPage()
    const rows = wrapper.findAll('.el-table__row')
    expect(rows.length).toBe(2)
    const firingRow = rows.find((row) => row.text().includes('CtdsBackendDown'))
    const resolvedRow = rows.find((row) => row.text().includes('CtdsBackendHighErrorRate'))
    expect(firingRow?.text()).toContain('已触发')
    expect(resolvedRow?.text()).toContain('已恢复')
    // 徽标类型断言（评审④ P3 补强：firing=danger / resolved=info 颜色映射有人拦）
    const firingTag = firingRow?.findAll('.el-tag')[1]
    const resolvedTag = resolvedRow?.findAll('.el-tag')[1]
    expect(firingTag?.classes()).toContain('el-tag--danger')
    expect(resolvedTag?.classes()).toContain('el-tag--info')
    // 级别双态徽标：critical 严重 / warning 警告
    expect(firingRow?.text()).toContain('严重')
    expect(resolvedRow?.text()).toContain('警告')
  })
})

describe('告警与处置页（T12 处置对话框）', () => {
  it('处置按钮仅系统管理员视角渲染（审计管理员与安全管理员视角均无处置入口，S2-7 写面）', async () => {
    setAdminPersona('audadmin')
    const { wrapper } = await mountPage()
    expect(button(wrapper, '处置')).toBeUndefined()
    setAdminPersona('secadmin')
    const { wrapper: secWrapper } = await mountPage()
    expect(button(secWrapper, '处置')).toBeUndefined()
    setAdminPersona('sysadmin')
    const { wrapper: sysWrapper } = await mountPage()
    expect(button(sysWrapper, '处置')).toBeDefined()
  })

  it('对话框：动作单选三选项；时长输入仅静默显示（缺省 30 / 上限 1440 控件约束）', async () => {
    const { wrapper } = await mountPage()
    await button(wrapper, '处置')?.trigger('click')
    await flushPromises()
    const dialog = wrapper.find('.el-dialog')
    expect(dialog.exists()).toBe(true)
    // 动作单选：确认 / 静默 / 升级
    const radios = wrapper.findAll('.el-radio')
    expect(radios.map((r) => r.text())).toEqual(expect.arrayContaining(['确认', '静默', '升级']))
    // 默认动作非静默 → 时长输入不显示
    const durationBefore = wrapper.findComponent({ name: 'ElInputNumber' })
    expect(durationBefore.exists()).toBe(false)
    // 切到静默 → 时长输入显示，缺省 30 / min 1 / max 1440
    await radios.find((r) => r.text().includes('静默'))?.trigger('click')
    await flushPromises()
    const duration = wrapper.findComponent({ name: 'ElInputNumber' })
    expect(duration.exists()).toBe(true)
    expect(duration.props('max')).toBe(1440)
    expect(duration.props('min')).toBe(1)
    expect(duration.props('modelValue')).toBe(30)
  })

  it('提交静默处置：请求体断言（durationMinutes 仅静默携带）+ 回显卡 result=OK/silenceId/recordedAt + 列表刷新', async () => {
    mockedDispose.mockResolvedValue({ alertName: 'CtdsBackendDown', action: 'SILENCE', result: 'OK', silenceId: 'SL-1001', recordedAt: '2026-11-15T11:05:00' })
    const { wrapper } = await mountPage()
    await button(wrapper, '处置')?.trigger('click')
    await flushPromises()
    // 选择静默动作 + 备注
    const radios = wrapper.findAll('.el-radio')
    await radios.find((r) => r.text().includes('静默'))?.trigger('click')
    await flushPromises()
    const textarea = wrapper.find('.el-dialog textarea')
    await textarea.setValue('窗口期处置')
    // 提交
    mockedAlerts.mockClear()
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedDispose).toHaveBeenCalledWith({
      alertName: 'CtdsBackendDown',
      action: 'SILENCE',
      note: '窗口期处置',
      durationMinutes: 30,
    })
    // 回显卡：result=OK + silenceId（等宽）+ recordedAt
    const text = document.body.textContent ?? ''
    expect(text).toContain('OK')
    expect(text).toContain('SL-1001')
    expect(text).toContain('2026-11-15T11:05:00')
    // 列表刷新（处置后重拉）
    expect(mockedAlerts).toHaveBeenCalled()
  })

  it('提交升级处置：回显卡附"通知外发通道待生效（G-04）"提示；durationMinutes 不传', async () => {
    mockedDispose.mockResolvedValue({ alertName: 'CtdsBackendDown', action: 'ESCALATE', result: 'OK', silenceId: null, recordedAt: '2026-11-15T11:06:00' })
    const { wrapper } = await mountPage()
    await button(wrapper, '处置')?.trigger('click')
    await flushPromises()
    const radios = wrapper.findAll('.el-radio')
    await radios.find((r) => r.text().includes('升级'))?.trigger('click')
    await flushPromises()
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    const call = mockedDispose.mock.calls[0][0]
    expect(call.action).toBe('ESCALATE')
    expect(call.durationMinutes).toBeUndefined()
    expect(document.body.textContent).toContain('待生效')
    expect(document.body.textContent).toContain('平台留痕')
  })

  it('处置失败：503 envelope → 服务端文案原样（E-1 实测形态，不吞错）', async () => {
    mockedDispose.mockRejectedValue(new ApiError('1013S0003', '监控数据源暂不可用，请稍后重试'))
    const { wrapper } = await mountPage()
    await button(wrapper, '处置')?.trigger('click')
    await flushPromises()
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('监控数据源暂不可用，请稍后重试')
  })
})
