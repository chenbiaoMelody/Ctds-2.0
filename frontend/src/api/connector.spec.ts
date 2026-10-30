import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  applyConnector,
  amendConnector,
  listMyRegistrations,
  listReviewQueue,
  verifyRegistration,
  withdrawRegistration,
  listMyConnectors,
  heartbeat,
  getBinding,
  listActionLogs,
  getLifecycle,
  requestUpgrade,
  listPendingUpgrades,
  confirmUpgrade,
  cancelConnector,
  listSources,
  getSource,
  registerSource,
  amendSource,
  checkSource,
  listGovernanceConnectors,
} from './connector'
import { ApiError, setDemoSubject } from './client'
import { setActorMode } from '../stores/demoIdentity'

/**
 * WBS-3.6.7 连接器域 API 测试（hifi §1 / §8.1 T1 组）：
 * 21 端点封装路径 / 方法 / 分页参数 / 路径段 encodeURIComponent 逐字；
 * 域角色头（普通档 applicant / 运营档 admin）；**无幂等键头**（连接器域未启用幂等）；
 * 空主体前置拦截零请求；内部面零调用。
 */
const fetchMock = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockResolvedValue({
    json: () => Promise.resolve({ code: '0', message: 'success', data: null }),
  })
  setDemoSubject('S20261026000041')
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

describe('connector API 请求形态（T1 组）', () => {
  it('applyConnector：POST /registrations + 三要素 body；角色头 applicant；无幂等键', async () => {
    await applyConnector({ connectorName: '星河数据连接器', connectorType: 'STANDARD', connectorVersion: '1.0.0' })
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/connectors/registrations')
    expect(options?.method).toBe('POST')
    expect(JSON.parse(options?.body as string)).toEqual({
      connectorName: '星河数据连接器',
      connectorType: 'STANDARD',
      connectorVersion: '1.0.0',
    })
    expect(lastHeaders()['X-Ctds-Roles']).toBe('applicant')
    expect(lastHeaders()['X-Idempotency-Key']).toBeUndefined()
  })

  it('运营档：角色头 = admin（治理面动作）', async () => {
    setActorMode('operator')
    await listReviewQueue(1, 10)
    expect(lastHeaders()['X-Ctds-Roles']).toBe('admin')
    expect(lastHeaders()['X-Ctds-Roles']).not.toContain('connector-internal')
  })

  it('amendConnector：PUT /registrations/{no}（请求体三要素）', async () => {
    await amendConnector('CA000001', {
      connectorName: '补正名',
      connectorType: 'STANDARD',
      connectorVersion: '1.0.1',
    })
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/connectors/registrations/CA000001')
    expect(options?.method).toBe('PUT')
    expect(JSON.parse(options?.body as string)).toEqual({
      connectorName: '补正名',
      connectorType: 'STANDARD',
      connectorVersion: '1.0.1',
    })
  })

  it('listMyRegistrations：GET registrations/mine + pageQuery', async () => {
    await listMyRegistrations(2, 10)
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/connectors/registrations/mine?pageNum=2&pageSize=10')
    expect(options?.method).toBeUndefined()
  })

  it('listReviewQueue：GET review/queue + pageQuery', async () => {
    await listReviewQueue(1, 10)
    expect(lastCall()[0]).toBe('/api/v1/connectors/review/queue?pageNum=1&pageSize=10')
  })

  it('verifyRegistration：POST verification（空 body）', async () => {
    await verifyRegistration('CA000001')
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/connectors/registrations/CA000001/verification')
    expect(options?.method).toBe('POST')
    expect(options?.body).toBeUndefined()
  })

  it('withdrawRegistration：DELETE /registrations/{no}', async () => {
    await withdrawRegistration('CA000001')
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/connectors/registrations/CA000001')
    expect(options?.method).toBe('DELETE')
  })

  it('listMyConnectors：GET mine + pageQuery', async () => {
    await listMyConnectors(1, 10)
    expect(lastCall()[0]).toBe('/api/v1/connectors/mine?pageNum=1&pageSize=10')
  })

  it('heartbeat：POST /{no}/heartbeats（空 body）', async () => {
    await heartbeat('CN000001')
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/connectors/CN000001/heartbeats')
    expect(options?.method).toBe('POST')
    expect(options?.body).toBeUndefined()
  })

  it('getBinding / getLifecycle：GET 路径逐字', async () => {
    await getBinding('CN000001')
    expect(lastCall()[0]).toBe('/api/v1/connectors/CN000001/binding')
    await getLifecycle('CN000001')
    expect(lastCall()[0]).toBe('/api/v1/connectors/CN000001/lifecycle')
  })

  it('listActionLogs：GET action-logs + pageQuery', async () => {
    await listActionLogs('CN000001', 1, 5)
    expect(lastCall()[0]).toBe('/api/v1/connectors/CN000001/action-logs?pageNum=1&pageSize=5')
  })

  it('requestUpgrade：POST /{no}/upgrades + body（无幂等键）', async () => {
    await requestUpgrade('CN000001', { toVersion: '2.0.0', compatibility: 'INCOMPATIBLE' })
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/connectors/CN000001/upgrades')
    expect(options?.method).toBe('POST')
    expect(JSON.parse(options?.body as string)).toEqual({
      toVersion: '2.0.0',
      compatibility: 'INCOMPATIBLE',
    })
    expect(lastHeaders()['X-Idempotency-Key']).toBeUndefined()
  })

  it('listPendingUpgrades：GET upgrades/pending + pageQuery', async () => {
    await listPendingUpgrades(1, 10)
    expect(lastCall()[0]).toBe('/api/v1/connectors/upgrades/pending?pageNum=1&pageSize=10')
  })

  it('confirmUpgrade：POST /{no}/upgrades/{upgradeNo}/confirmation', async () => {
    await confirmUpgrade('CN000001', 'UP000002')
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/connectors/CN000001/upgrades/UP000002/confirmation')
    expect(options?.method).toBe('POST')
  })

  it('cancelConnector：POST /{no}/cancellation', async () => {
    await cancelConnector('CN000001')
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/connectors/CN000001/cancellation')
    expect(options?.method).toBe('POST')
  })

  it('listSources / getSource：GET 源清单与详情', async () => {
    await listSources('CN000001', 1, 10)
    expect(lastCall()[0]).toBe('/api/v1/connectors/CN000001/sources?pageNum=1&pageSize=10')
    await getSource('CN000001', 'SR000003')
    expect(lastCall()[0]).toBe('/api/v1/connectors/CN000001/sources/SR000003')
  })

  it('registerSource：POST /sources + 凭据仅请求侧', async () => {
    await registerSource('CN000001', {
      sourceType: 'DATABASE',
      endpoint: 'jdbc:mysql://127.0.0.1:3306/demo',
      credential: 'secret-001',
    })
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/connectors/CN000001/sources')
    expect(options?.method).toBe('POST')
    expect(JSON.parse(options?.body as string)).toEqual({
      sourceType: 'DATABASE',
      endpoint: 'jdbc:mysql://127.0.0.1:3306/demo',
      credential: 'secret-001',
    })
  })

  it('amendSource：PUT /sources/{no}；凭据留空 = 不携带该字段', async () => {
    await amendSource('CN000001', 'SR000003', { endpoint: 'jdbc:mysql://127.0.0.1:3306/demo2' })
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/connectors/CN000001/sources/SR000003')
    expect(options?.method).toBe('PUT')
    expect(JSON.parse(options?.body as string)).toEqual({
      endpoint: 'jdbc:mysql://127.0.0.1:3306/demo2',
    })
    await amendSource('CN000001', 'SR000003', {
      endpoint: 'jdbc:mysql://127.0.0.1:3306/demo2',
      credential: 'secret-002',
    })
    expect(JSON.parse(lastCall()[1]?.body as string)).toEqual({
      endpoint: 'jdbc:mysql://127.0.0.1:3306/demo2',
      credential: 'secret-002',
    })
  })

  it('checkSource：POST /sources/{no}/checks（空 body）', async () => {
    await checkSource('CN000001', 'SR000003')
    const [path, options] = lastCall()
    expect(path).toBe('/api/v1/connectors/CN000001/sources/SR000003/checks')
    expect(options?.method).toBe('POST')
  })

  it('listGovernanceConnectors：GET governance/connectors + status 过滤（空 = 不带参数）', async () => {
    await listGovernanceConnectors('REGISTERED', 1, 10)
    expect(lastCall()[0]).toBe(
      '/api/v1/connectors/governance/connectors?pageNum=1&pageSize=10&status=REGISTERED',
    )
    await listGovernanceConnectors('', 1, 10)
    expect(lastCall()[0]).toBe('/api/v1/connectors/governance/connectors?pageNum=1&pageSize=10')
  })

  it('路径段编号 encodeURIComponent（防拼接注入）', async () => {
    await getLifecycle('CN 000001')
    expect(lastCall()[0]).toBe('/api/v1/connectors/CN%20000001/lifecycle')
    await confirmUpgrade('CN000001', 'UP/000002')
    expect(lastCall()[0]).toBe('/api/v1/connectors/CN000001/upgrades/UP%2F000002/confirmation')
  })

  it('空主体：前置拦截零请求（ApiError 1000C0002）', async () => {
    setDemoSubject('   ')
    await expect(listMyConnectors(1, 10)).rejects.toBeInstanceOf(ApiError)
    await expect(listMyConnectors(1, 10)).rejects.toMatchObject({ code: '1000C0002' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('内部面零调用：21 个封装路径均不含 /internal/', async () => {
    const calls: Array<() => Promise<unknown>> = [
      () => applyConnector({ connectorName: 'a', connectorType: 'STANDARD', connectorVersion: '1' }),
      () => amendConnector('CA000001', { connectorName: 'a', connectorType: 'STANDARD', connectorVersion: '1' }),
      () => listMyRegistrations(1, 10),
      () => listReviewQueue(1, 10),
      () => verifyRegistration('CA000001'),
      () => withdrawRegistration('CA000001'),
      () => listMyConnectors(1, 10),
      () => heartbeat('CN000001'),
      () => getBinding('CN000001'),
      () => listActionLogs('CN000001', 1, 10),
      () => getLifecycle('CN000001'),
      () => requestUpgrade('CN000001', { toVersion: '2.0.0', compatibility: 'COMPATIBLE' }),
      () => listPendingUpgrades(1, 10),
      () => confirmUpgrade('CN000001', 'UP000002'),
      () => cancelConnector('CN000001'),
      () => listSources('CN000001', 1, 10),
      () => getSource('CN000001', 'SR000003'),
      () => registerSource('CN000001', { sourceType: 'DATABASE', endpoint: 'jdbc:x', credential: 's' }),
      () => amendSource('CN000001', 'SR000003', { endpoint: 'jdbc:y' }),
      () => checkSource('CN000001', 'SR000003'),
      () => listGovernanceConnectors('', 1, 10),
    ]
    for (const call of calls) {
      await call()
      expect(lastCall()[0]).not.toContain('/internal/')
    }
    expect(calls.length).toBe(21)
  })
})
