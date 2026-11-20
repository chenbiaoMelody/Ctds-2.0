import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  fetchAiConfig,
  fetchAiConfigChanges,
  fetchAiConfigPreview,
  saveAiConfig,
  AI_CONFIG_URL,
  AI_CONFIG_SAVE_URL,
  AI_CONFIG_PREVIEW_URL,
  AI_CONFIG_CHANGES_URL,
  type AiConfigView,
} from './ai'
import { setDemoSubject } from './client'
import { setActorMode } from '../stores/demoIdentity'

/**
 * AI 配置域 API 单元（WBS-3.10.4 hifi §4 / T 静态面；沿 api/platform.spec 先例）：
 * - URL 单点：4 端点 URL 仅存在于 `api/ai.ts`（SourceGuard 口径：视图零 URL）；
 * - 域工厂：复用 `makeDomainJson(platformRolesHeader)`（platform 域随三员视角，sysadmin 缺省）；
 * - 保存 = 全量 PUT（hifi §1.2）；留痕分页走共享 pageQuery（DB-105）。
 */

type FetchCall = { path: string; init: RequestInit }

const fetchMock = vi.fn()

function apiOk(data: unknown): Response {
  const body = JSON.stringify({ code: '0', message: 'success', data })
  return {
    ok: true,
    status: 200,
    json: async () => JSON.parse(body),
  } as unknown as Response
}

async function lastCall(): Promise<FetchCall> {
  expect(fetchMock).toHaveBeenCalled()
  const calls = fetchMock.mock.calls as unknown as [string, RequestInit][]
  const [path, init] = calls[calls.length - 1]
  return { path, init: init ?? {} }
}

beforeEach(() => {
  localStorage.clear()
  setDemoSubject('S-admin-platform')
  setActorMode('operator')
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('api/ai：URL 单点 + 全量保存', () => {
  it('fetchAiConfig：GET /api/v1/ai-config，域头随三员视角（缺省 platform.sysadmin）', async () => {
    fetchMock.mockResolvedValue(apiOk({ enabled: false, defaultProvider: 'glm', tokenBudget: 0, providers: [] }))
    await fetchAiConfig()
    const { path, init } = await lastCall()
    expect(path).toBe('/api/v1/ai-config')
    expect(init.method).toBeUndefined()
    expect((init.headers as Record<string, string>)['X-Ctds-Roles']).toBe('platform.sysadmin')
  })

  it('saveAiConfig：POST /save 全量载荷（enabled/defaultProvider/tokenBudget/providers 四行；写面沿仓内 POST 惯例）', async () => {
    fetchMock.mockResolvedValue(apiOk({ enabled: true, defaultProvider: 'glm', tokenBudget: 1, providers: [] }))
    const req = {
      enabled: true,
      defaultProvider: 'glm',
      tokenBudget: 1,
      providers: [
        { providerId: 'glm', baseUrl: 'https://open.example.com', model: 'glm-4-flash' },
        { providerId: 'deepseek', baseUrl: null, model: null },
        { providerId: 'qwen', baseUrl: null, model: null },
        { providerId: 'kimi', baseUrl: null, model: null },
      ],
    }
    await saveAiConfig(req)
    const { path, init } = await lastCall()
    expect(path).toBe('/api/v1/ai-config/save')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual(req)
  })

  it('fetchAiConfigPreview：GET /preview；fetchAiConfigChanges：GET /changes 分页串', async () => {
    fetchMock.mockResolvedValue(apiOk({ decision: 'ALLOW', reason: '允许' }))
    await fetchAiConfigPreview()
    expect((await lastCall()).path).toBe('/api/v1/ai-config/preview')
    fetchMock.mockResolvedValue(apiOk({ list: [], total: 0, pageNum: 2, pageSize: 10, totalPages: 0 }))
    const page = await fetchAiConfigChanges(2, 10)
    expect((await lastCall()).path).toBe('/api/v1/ai-config/changes?pageNum=2&pageSize=10')
    expect(page.total).toBe(0)
  })

  it('URL 常量单点（SourceGuard：四常量与函数内路径一致）', () => {
    expect(AI_CONFIG_URL).toBe('/api/v1/ai-config')
    expect(AI_CONFIG_SAVE_URL).toBe('/api/v1/ai-config/save')
    expect(AI_CONFIG_PREVIEW_URL).toBe('/api/v1/ai-config/preview')
    expect(AI_CONFIG_CHANGES_URL).toBe('/api/v1/ai-config/changes')
  })

  it('类型锚：AiConfigView.providers 行含 providerName/isDefaultProvider/configured（hifi §1.1）', () => {
    const view: AiConfigView = {
      enabled: false,
      defaultProvider: 'glm',
      tokenBudget: 0,
      providers: [{ providerId: 'glm', providerName: '智谱 GLM', isDefaultProvider: true, baseUrl: null, model: null, configured: false }],
    }
    expect(view.providers[0].providerName).toBe('智谱 GLM')
    expect(view.providers[0].isDefaultProvider).toBe(true)
  })
})
