import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import AiConfigView from './AiConfigView.vue'
import {
  fetchAiConfig,
  saveAiConfig,
  fetchAiConfigPreview,
  fetchAiConfigChanges,
  type AiConfigView as AiConfigViewData,
  type AiConfigChangeLogView,
} from '../../api/ai'
import { ApiError } from '../../api/client'
import { validateAiConfigForm } from '../../constants/ai'

/**
 * AI 服务配置页测试（WBS-3.10.4 hifi §7 F1~F6 + 评审修复批 F7；lofi 四区块；C-11.1A B1~B4）：
 * - F1 缺省渲染（C-11.1 缺省 + 未配置灰字 + 密钥固定灰字零输入框 + 诚实边界条）；
 * - F2 编辑保存（全量 POST /save payload + 成功反馈 + 视图/留痕刷新）；
 * - F3 前置校验（负预算 / 非 http(s) / 模型名超长 → 零请求）；
 * - F5 判定预览三态芯片与"已用量恒为 0"口径灰字；
 * - F6 变更留痕渲染与空态（>10 行分页控件渲染）；
 * - F7 留痕加载失败显式提示（评审 P3-E：失败态与空态可区分，不吞错）。
 */
vi.mock('../../api/ai', () => ({
  fetchAiConfig: vi.fn(),
  saveAiConfig: vi.fn(),
  fetchAiConfigPreview: vi.fn(),
  fetchAiConfigChanges: vi.fn(),
  AI_CONFIG_URL: '/api/v1/ai-config',
  AI_CONFIG_SAVE_URL: '/api/v1/ai-config/save',
  AI_CONFIG_PREVIEW_URL: '/api/v1/ai-config/preview',
  AI_CONFIG_CHANGES_URL: '/api/v1/ai-config/changes',
}))

const mockedFetch = vi.mocked(fetchAiConfig)
const mockedSave = vi.mocked(saveAiConfig)
const mockedPreview = vi.mocked(fetchAiConfigPreview)
const mockedChanges = vi.mocked(fetchAiConfigChanges)

function provider(over: Record<string, unknown> = {}): AiConfigViewData['providers'][number] {
  return {
    providerId: 'glm',
    providerName: '智谱 GLM',
    isDefaultProvider: true,
    baseUrl: null,
    model: null,
    configured: false,
    ...over,
  }
}

function view(over: Partial<AiConfigViewData> = {}): AiConfigViewData {
  return {
    enabled: false,
    defaultProvider: 'glm',
    tokenBudget: 0,
    providers: [
      provider(),
      provider({ providerId: 'deepseek', providerName: 'DeepSeek', isDefaultProvider: false }),
      provider({ providerId: 'qwen', providerName: '通义千问 Qwen', isDefaultProvider: false }),
      provider({ providerId: 'kimi', providerName: 'Kimi', isDefaultProvider: false }),
    ],
    ...over,
  }
}

function changeRow(over: Partial<AiConfigChangeLogView> = {}): AiConfigChangeLogView {
  return {
    changedAt: '2026-11-21 10:00:00',
    changedBy: 'admin',
    fieldPath: 'tokenBudget',
    beforeValue: null,
    afterValue: '100000',
    ...over,
  }
}

async function mountPage(): Promise<ReturnType<typeof mount>> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/platform/ai-config', name: 'platform-ai-config', component: AiConfigView }],
  })
  await router.push('/platform/ai-config')
  await router.isReady()
  const wrapper = mount(AiConfigView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.clearAllMocks()
  mockedFetch.mockResolvedValue(view())
  mockedPreview.mockResolvedValue({ decision: 'DENIED_DISABLED', reason: '拒绝：网关未启用' })
  mockedChanges.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 })
})

describe('AI 服务配置页（WBS-3.10.4）', () => {
  it('F1：缺省渲染——开关关/预算 0/glm 默认/四供应商"未配置"/密钥固定灰字/诚实边界条', async () => {
    const wrapper = await mountPage()
    const text = wrapper.text()
    expect(text).toContain('配置即预留')
    // el-switch 自定义类落在根节点（.ai-config-enabled 即 el-switch 根）
    expect(wrapper.find('.ai-config-enabled').classes()).not.toContain('is-checked')
    const budgetInput = wrapper.find('.ai-config-budget input').element as HTMLInputElement
    expect(budgetInput.value).toContain('0')
    expect(wrapper.find('.ai-config-default-provider').exists()).toBe(true)
    // 未配置态以输入框占位符呈现（placeholder 属性，非文本节点）
    const baseInputs = wrapper.findAll('.provider-base-url input')
    expect(baseInputs.length).toBe(4)
    expect(baseInputs.every((i) => (i.element as HTMLInputElement).placeholder === '未配置')).toBe(true)
    expect(text).toContain('部署期经配置中心/KMS 注入（页面不显示）')
    // 密钥红线：页面零密钥输入框
    expect(wrapper.findAll('input[type="password"]').length).toBe(0)
    wrapper.unmount()
  })

  it('F2：编辑保存——全量 POST /save payload + 成功反馈 + 视图与留痕刷新', async () => {
    const successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    mockedSave.mockResolvedValue(view({ enabled: true, tokenBudget: 100000 }))
    const wrapper = await mountPage()
    await wrapper.find('.ai-config-enabled').trigger('click')
    await flushPromises()
    await wrapper.find('.ai-config-save').trigger('click')
    await flushPromises()
    expect(mockedSave).toHaveBeenCalledTimes(1)
    const payload = mockedSave.mock.calls[0][0]
    expect(payload.enabled).toBe(true)
    expect(payload.defaultProvider).toBe('glm')
    expect(payload.tokenBudget).toBe(0)
    expect(payload.providers).toHaveLength(4)
    expect(mockedFetch).toHaveBeenCalledTimes(2)
    // P3-I 补强（评审④-3）：成功反馈与留痕刷新均有断言（题述与断言对齐）
    expect(successSpy).toHaveBeenCalledTimes(1)
    expect(String(successSpy.mock.calls[0][0])).toContain('已保存')
    expect(mockedChanges).toHaveBeenCalledTimes(2)
    successSpy.mockRestore()
    wrapper.unmount()
  })

  it('F3：前置校验——负预算 / 非 http(s) 地址 / 模型名超长 → 拒绝提示且零请求（载荷形态 = 四行封闭清单）', () => {
    const rows = (overrides: Record<string, Record<string, unknown>> = {}) =>
      ['glm', 'deepseek', 'qwen', 'kimi'].map((providerId) => ({
        providerId, baseUrl: null, model: null, ...(overrides[providerId] ?? {}),
      }))
    expect(validateAiConfigForm({ defaultProvider: 'glm', tokenBudget: -1, providers: rows() })).toContain('预算')
    expect(validateAiConfigForm({ defaultProvider: 'glm', tokenBudget: 1, providers: rows({ glm: { baseUrl: 'ftp://x' } }) })).toContain('http(s)')
    expect(validateAiConfigForm({ defaultProvider: 'glm', tokenBudget: 1, providers: rows({ glm: { model: 'x'.repeat(129) } }) })).toContain('128')
    expect(validateAiConfigForm({ defaultProvider: 'gpt', tokenBudget: 1, providers: rows() })).toContain('封闭清单')
    expect(validateAiConfigForm({ defaultProvider: 'glm', tokenBudget: 1, providers: rows({ deepseek: { baseUrl: 'https://api.deepseek.com' } }) })).toBeNull()
    expect(mockedSave).not.toHaveBeenCalled()
  })

  it('F5：判定预览三态芯片 + "已用量恒为 0"口径灰字；保存错误原文呈现（1014 段）', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('.ai-config-preview-chip').text()).toContain('拒绝：网关未启用')
    expect(wrapper.text()).toContain('已用量恒为 0')
    mockedSave.mockRejectedValue(new ApiError('1014C0001', '服务地址须为 http(s) URL，或留空表示未配置'))
    await wrapper.find('.ai-config-save').trigger('click')
    await flushPromises()
    expect(wrapper.find('.ai-config-error').text()).toContain('1014C0001')
    expect(wrapper.text()).toContain('服务地址须为 http(s) URL')
    wrapper.unmount()
  })

  it('F6：变更留痕渲染五列；空态"暂无变更记录"；>10 行分页控件渲染', async () => {
    mockedChanges.mockResolvedValue({ list: [changeRow()], total: 1, pageNum: 1, pageSize: 10, totalPages: 1 })
    const wrapper = await mountPage()
    expect(wrapper.find('.ai-config-changes').text()).toContain('tokenBudget')
    expect(wrapper.find('.ai-config-changes').text()).toContain('（空）')
    wrapper.unmount()
    mockedChanges.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 })
    const empty = await mountPage()
    expect(empty.find('.ai-config-changes').text()).toContain('暂无变更记录')
    empty.unmount()
    // P3-J 补强（评审④-4）：留痕 >10 行 → 分页控件渲染（剧本 S4-1 组件级支撑）
    mockedChanges.mockResolvedValue({
      list: Array.from({ length: 12 }, (_, index) => changeRow({ fieldPath: `tokenBudget#${index + 1}` })),
      total: 12,
      pageNum: 1,
      pageSize: 10,
      totalPages: 2,
    })
    const paged = await mountPage()
    expect(paged.find('.pager').exists()).toBe(true)
    paged.unmount()
  })

  it('F7：留痕加载失败显式提示（评审 P3-E：失败态与"暂无变更记录"空态可区分，不吞错）', async () => {
    const errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    mockedChanges.mockRejectedValue(new Error('changes unavailable'))
    const wrapper = await mountPage()
    expect(errorSpy).toHaveBeenCalledTimes(1)
    expect(String(errorSpy.mock.calls[0][0])).toContain('变更留痕')
    wrapper.unmount()
    errorSpy.mockRestore()
  })
})
