import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import DomainView from './DomainView.vue'
import { getDomain, type DomainView as DomainViewData } from '../../api/platform'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode, setAdminPersona } from '../../stores/demoIdentity'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'

/**
 * 部署域信息页测试（WBS-3.9.5 hifi §8 T6/T7；lofi 屏 D；C-10.1 S2-1/S3-1~S3-5）：
 * - T6 八字段 descriptions：scopeNote 原文 + lifecycleStatus/configConsistency 徽标映射；
 *   **零操作按钮（结构断言，B9-R3 生命周期无在线 API）**；
 * - T7 fail-closed：503 + 1013S0002 原文整页错误态 + 重试，不渲染"正常"态（B9-R4）；
 * - 退役态：RETIRED 灰徽标 + 信息卡照常（端点豁免可读，S3-5）。
 */
vi.mock('../../api/platform', () => ({
  getDomain: vi.fn(),
}))

const mockedDomain = vi.mocked(getDomain)

function domain(over: Partial<DomainViewData> = {}): DomainViewData {
  return {
    domainId: 'domain-01',
    domainName: '城市可信数据空间（演示部署域）',
    platformVersion: 'V1.0',
    lifecycleStatus: 'ACTIVE',
    schemaList: ['ctds_subject', 'ctds_space'],
    serviceList: ['subject-service', 'evidence-service'],
    configConsistency: 'NOT_APPLICABLE',
    scopeNote: 'V1.0 = 单实例单部署域，生命周期 = 配置版本与状态管理，不做多实例自动开通',
    ...over,
  }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/platform/domain', name: 'platform-domain', component: DomainView }],
  })
  await router.push('/platform/domain')
  await router.isReady()
  const wrapper = mount(DomainView, {
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
  mockedDomain.mockReset()
  setDemoSubject('S-admin-platform')
  setActorMode('operator')
  setAdminPersona('sysadmin')
  if (!isDemoAuthed()) {
    signInDemo()
  }
  mockedDomain.mockResolvedValue(domain())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('部署域信息页（T6 八字段只读）', () => {
  it('挂载即拉 domain；八字段渲染：scopeNote 原文 + 双徽标 + 清单列表', async () => {
    const { wrapper } = await mountPage()
    expect(mockedDomain).toHaveBeenCalledTimes(1)
    const text = document.body.textContent ?? ''
    expect(text).toContain('domain-01')
    expect(text).toContain('城市可信数据空间（演示部署域）')
    expect(text).toContain('V1.0')
    expect(text).toContain('ctds_subject')
    expect(text).toContain('subject-service')
    // scopeNote 原文透传（口径声明，不转译）
    expect(text).toContain('V1.0 = 单实例单部署域，生命周期 = 配置版本与状态管理，不做多实例自动开通')
    // 生命周期 ACTIVE 徽标 + 一致性 NOT_APPLICABLE 徽标
    expect(text).toContain('生效中')
    expect(text).toContain('不适用')
    // 零操作按钮（B9-R3：生命周期动作由部署变更执行，页面只做状态呈现）
    expect(wrapper.findAll('button').length).toBe(0)
  })

  it('退役态：RETIRED 灰徽标 + 信息卡照常呈现（端点豁免可读，S3-5）', async () => {
    mockedDomain.mockResolvedValue(domain({ lifecycleStatus: 'RETIRED' }))
    const { wrapper } = await mountPage()
    const text = document.body.textContent ?? ''
    expect(text).toContain('已退役')
    expect(text).not.toContain('生效中')
    // 信息卡照常：scopeNote 仍在
    expect(text).toContain('单实例单部署域')
    expect(wrapper.findAll('button').length).toBe(0)
  })

  it('越权 403：非系统管理员视角 → ApiError 原文错误态（不渲染信息卡）', async () => {
    setAdminPersona('secadmin')
    mockedDomain.mockRejectedValue(new ApiError('1000C0005', '无权限执行该操作'))
    const { wrapper } = await mountPage()
    expect(document.body.textContent).toContain('无权限执行该操作')
    expect(document.body.textContent).not.toContain('domain-01')
    expect(button(wrapper, '重试')).toBeDefined()
  })
})

describe('部署域信息页（T7 fail-closed，B9-R4）', () => {
  it('配置不一致 503 + 1013S0002：整页错误态原文 + 重试；不渲染"生效中"（不显示正常态）', async () => {
    mockedDomain.mockRejectedValue(new ApiError('1013S0002', '部署域配置与实际不一致，请联系系统管理员'))
    const { wrapper } = await mountPage()
    const text = document.body.textContent ?? ''
    expect(text).toContain('部署域配置与实际不一致，请联系系统管理员')
    // fail-closed：错误态不渲染信息卡（无生命周期标签 = 未显示"正常"）
    expect(text).not.toContain('生效中')
    expect(text).not.toContain('domain-01')
    // 重试恢复
    mockedDomain.mockResolvedValue(domain())
    await button(wrapper, '重试')?.trigger('click')
    await flushPromises()
    expect(mockedDomain).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('domain-01')
  })
})
