import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  getContractDetail,
  initiateContract,
  listContractActionLogs,
  listTemplates,
} from './contract'
import { searchProducts } from './catalog'
import { ApiError, setDemoSubject } from './client'
import { contractRolesHeader } from '../stores/demoIdentity'
import { setActorMode } from '../stores/demoIdentity'

/**
 * 合约域演示身份与角色头测试（WBS-3.4.7 hifi §4 + §7 T18）：
 * - 普通档 `contractRolesHeader()` = `provider`（**断言不含 `admin`**——硬约束：普通档携带
 *   admin 会让模板维护类与"越权被拒"类剧本步骤被服务端放行，让判定失真）；
 * - 运营档 = `admin`；单点承载（页面级头只从 demoIdentity 出）；
 * - 空主体编号 → 前置拦截零请求（`1000C0002`，沿 3.2.6 E3 口径）；
 * - 页面级角色头覆盖 `apiJson` 全局头（`X-Ctds-Roles` = 合约域映射，非全局 demoRolesHeader）；
 * - 跨域产品检索（发起弹窗）走目录域角色头（互不污染）。
 */

interface CapturedRequest {
  url: string
  method: string
  headers: Record<string, string>
}

function stubFetchCapture(): CapturedRequest[] {
  const calls: CapturedRequest[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const raw = (init?.headers ?? {}) as Record<string, string>
    const headers: Record<string, string> = {}
    if (typeof Headers !== 'undefined' && raw instanceof Headers) {
      raw.forEach((value, key) => { headers[key] = value })
    } else {
      Object.assign(headers, raw)
    }
    calls.push({ url: String(input), method: init?.method ?? 'GET', headers })
    return new Response(JSON.stringify({ code: '0', message: 'success', data: {} }),
      { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
  return calls
}

beforeEach(() => {
  localStorage.clear()
  setDemoSubject('S20260925000001')
  setActorMode('subject')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('contractRolesHeader 单点（T18）', () => {
  it('普通档 = provider，且硬约束不得携带 admin', () => {
    setActorMode('subject')
    const header = contractRolesHeader()
    expect(header).toBe('provider')
    expect(header.split(',')).not.toContain('admin')
  })

  it('运营档 = admin（模板维护与治理面）', () => {
    setActorMode('operator')
    expect(contractRolesHeader()).toBe('admin')
  })
})

describe('页面级角色头与空主体前置拦截（T18）', () => {
  it('合约域请求附 X-Ctds-Roles = provider（普通档，覆盖全局演示角色头）', async () => {
    const calls = stubFetchCapture()
    await getContractDetail('CO000013')
    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/api/v1/contracts/CO000013')
    expect(calls[0].headers['X-Ctds-Roles']).toBe('provider')
    expect(calls[0].headers['X-Ctds-Subject']).toBe('S20260925000001')
  })

  it('运营档请求附 X-Ctds-Roles = admin', async () => {
    setActorMode('operator')
    const calls = stubFetchCapture()
    await listTemplates({ pageNum: 1, pageSize: 20 })
    expect(calls[0].headers['X-Ctds-Roles']).toBe('admin')
  })

  it('空主体编号 → 前置拦截零请求（1000C0002；写面同口径）', async () => {
    const calls = stubFetchCapture()
    setDemoSubject('   ')
    await expect(getContractDetail('CO000013')).rejects.toMatchObject({
      code: '1000C0002',
    } satisfies Partial<ApiError>)
    await expect(initiateContract({} as never)).rejects.toMatchObject({
      code: '1000C0002',
    } satisfies Partial<ApiError>)
    expect(calls).toHaveLength(0)
  })

  it('跨域产品检索走目录域角色头（普通档 provider；与合约域头互不污染）', async () => {
    const calls = stubFetchCapture()
    await searchProducts(1, 10, '餐饮')
    expect(calls).toHaveLength(1)
    expect(calls[0].url).toContain('/api/v1/data-products')
    expect(calls[0].headers['X-Ctds-Roles']).toBe('provider')
  })

  it('R13 留痕读面走合约域角色头与分页传参', async () => {
    const calls = stubFetchCapture()
    await listContractActionLogs({ contractNo: 'CO000013', pageNum: 1, pageSize: 10 })
    expect(calls[0].url).toBe('/api/v1/contracts/CO000013/action-logs?pageNum=1&pageSize=10')
    expect(calls[0].headers['X-Ctds-Roles']).toBe('provider')
  })
})
