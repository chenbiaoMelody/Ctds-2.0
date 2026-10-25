import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  requestDelivery,
  listDeliveries,
  getDelivery,
  requestSandbox,
  listSandboxes,
  closeSandbox,
  runSandboxJob,
  submitResultReview,
  exportSandboxResult,
  listGovernanceSandboxes,
  reviewGovernanceResult,
  listDeliveryActionLogs,
  listGovernanceDeliveryActionLogs,
} from './delivery'
import { setDemoSubject } from './client'
import { setActorMode } from '../stores/demoIdentity'

/**
 * WBS-3.5.6 交付域 API 测试（hifi §2.1 / §7 T1 组）：
 * 22 端点封装路径与参数逐字、幂等键头透传（X-Idempotency-Key）、域角色头
 * （普通档 requester,provider 双头 / 运营档 admin）、路径段 encodeURIComponent、
 * 分页参数形态（pageQuery）、G7 双路径（参与方 + 治理）。
 * 拦截方式 = vi.stubGlobal('fetch')（apiJson 走全局 fetch；域头与幂等键头逐字断言）。
 */
const fetchMock = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockResolvedValue({
    json: () => Promise.resolve({ code: '0', message: 'success', data: null }),
  })
  setDemoSubject('S20260922000001')
  setActorMode('subject')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function lastCall(): [string, RequestInit] {
  return fetchMock.mock.calls.at(-1) as [string, RequestInit]
}

function lastHeaders(): Record<string, string> {
  return (lastCall()[1]?.headers ?? {}) as Record<string, string>
}

describe('delivery API 请求形态（T1 组）', () => {
  it('requestDelivery：POST /api/v1/deliveries + 幂等键头 + body 逐字', async () => {
    await requestDelivery('idem-001', {
      contractNo: 'CO000026',
      deliveryType: 'FILE',
      fileId: 'F13-DEMO-001',
      purpose: '风控建模',
      territory: '本地',
    })
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/deliveries')
    expect(options?.method).toBe('POST')
    expect(JSON.parse(options?.body as string)).toEqual({
      contractNo: 'CO000026',
      deliveryType: 'FILE',
      fileId: 'F13-DEMO-001',
      purpose: '风控建模',
      territory: '本地',
    })
    expect(lastHeaders()['X-Idempotency-Key']).toBe('idem-001')
    expect(lastHeaders()['X-Ctds-Roles']).toBe('requester,provider')
  })

  it('运营档 requestDelivery：角色头为 admin', async () => {
    setActorMode('operator')
    await requestDelivery('idem-002', { contractNo: 'CO000026', deliveryType: 'API' })
    expect(lastHeaders()['X-Ctds-Roles']).toBe('admin')
  })

  it('listDeliveries：GET + 过滤参数逐字（pageQuery 形态）', async () => {
    await listDeliveries({ pageNum: 2, pageSize: 10, contractNo: 'CO000026', deliveryType: 'FILE' })
    const [path, options] = lastCall()
    expect(path).toBe(
      '/api/v1/deliveries?pageNum=2&pageSize=10&contractNo=CO000026&deliveryType=FILE',
    )
    expect(options?.method).toBeUndefined()
  })

  it('getDelivery：路径段 encodeURIComponent', async () => {
    await getDelivery('DL 01')
    expect(lastCall()[0]).toBe('/api/v1/deliveries/DL%2001')
  })

  it('requestSandbox：POST /api/v1/sandboxes + 幂等键 + body', async () => {
    await requestSandbox('idem-s1', { contractNo: 'CO000026' })
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/sandboxes')
    expect(options?.method).toBe('POST')
    expect(JSON.parse(options?.body as string)).toEqual({ contractNo: 'CO000026' })
    expect(lastHeaders()['X-Idempotency-Key']).toBe('idem-s1')
  })

  it('listSandboxes：过滤（合约 / 状态）+ 分页', async () => {
    await listSandboxes({ pageNum: 1, pageSize: 10, contractNo: 'CO000026', status: 'RUNNING' })
    expect(lastCall()[0]).toBe(
      '/api/v1/sandboxes?pageNum=1&pageSize=10&contractNo=CO000026&status=RUNNING',
    )
  })

  it('closeSandbox：POST actions/close（无幂等键）', async () => {
    await closeSandbox('SB000004')
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/sandboxes/SB000004/actions/close')
    expect(options?.method).toBe('POST')
    expect(lastHeaders()['X-Idempotency-Key']).toBeUndefined()
  })

  it('runSandboxJob：POST actions/run-job + 幂等键 + jobId/purpose/territory', async () => {
    await runSandboxJob('idem-j1', 'SB000004', {
      jobId: 'dataset-profile',
      purpose: '风控建模',
      territory: '本地',
    })
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/sandboxes/SB000004/actions/run-job')
    expect(JSON.parse(options?.body as string)).toEqual({
      jobId: 'dataset-profile',
      purpose: '风控建模',
      territory: '本地',
    })
    expect(lastHeaders()['X-Idempotency-Key']).toBe('idem-j1')
  })

  it('submitResultReview：fileName 路径段编码 + 幂等键', async () => {
    await submitResultReview('idem-r1', 'SB000004', 'dataset profile.md')
    const [path, options] = lastCall()
    expect(path).toBe(
      '/api/v1/sandboxes/SB000004/results/dataset%20profile.md/actions/submit-review',
    )
    expect(options?.method).toBe('POST')
    expect(lastHeaders()['X-Idempotency-Key']).toBe('idem-r1')
  })

  it('exportSandboxResult：body 可选要素（undefined 不进 body 键）', async () => {
    await exportSandboxResult('idem-e1', 'SB000004', 'result.md', {})
    const body = JSON.parse(lastCall()[1]?.body as string)
    expect(body).toEqual({})
  })

  it('reviewGovernanceResult：G3 无幂等键 + conclusion/reason body', async () => {
    await reviewGovernanceResult('SB000004', 'result.md', {
      conclusion: 'REJECT',
      reason: '含未脱敏字段',
    })
    const [path, options] = lastCall()
    expect(path).toBe(
      '/api/v1/sandboxes/governance/SB000004/results/result.md/actions/review',
    )
    expect(JSON.parse(options?.body as string)).toEqual({
      conclusion: 'REJECT',
      reason: '含未脱敏字段',
    })
    expect(lastHeaders()['X-Idempotency-Key']).toBeUndefined()
  })

  it('listGovernanceSandboxes：治理路径 + 过滤', async () => {
    await listGovernanceSandboxes({ pageNum: 1, pageSize: 10, contractNo: 'CO000026' })
    expect(lastCall()[0]).toBe(
      '/api/v1/sandboxes/governance?pageNum=1&pageSize=10&contractNo=CO000026',
    )
  })

  it('G7a 参与方动作留痕：contractNo 路径段 + 分页', async () => {
    await listDeliveryActionLogs({ contractNo: 'CO000026', pageNum: 1, pageSize: 10 })
    expect(lastCall()[0]).toBe(
      '/api/v1/deliveries/CO000026/action-logs?pageNum=1&pageSize=10',
    )
  })

  it('G7b 治理动作留痕：治理路径', async () => {
    await listGovernanceDeliveryActionLogs({ contractNo: 'CO000026', pageNum: 1, pageSize: 10 })
    expect(lastCall()[0]).toBe(
      '/api/v1/deliveries/governance/CO000026/action-logs?pageNum=1&pageSize=10',
    )
  })

  it('空主体编号：全部请求前置拦截零请求（1000C0002）', async () => {
    setDemoSubject('  ')
    await expect(
      listDeliveries({ pageNum: 1, pageSize: 10 }),
    ).rejects.toMatchObject({ code: '1000C0002' })
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
