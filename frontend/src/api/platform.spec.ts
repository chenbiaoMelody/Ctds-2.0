import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  disposeAlert,
  getDomain,
  getMetrics,
  grantPermission,
  listAlerts,
  listRbacChanges,
  listRbacRoles,
  revokePermission,
} from './platform'
import { ApiError, setDemoSubject } from './client'
import { setAdminPersona, setActorMode } from '../stores/demoIdentity'

/**
 * platform 域 API 单元（WBS-3.9.5 hifi §3 / §8 T 系列编码前置；DB-41 口径）：
 * - URL 单点：rbac 4 端点 + platform 4 端点全部 URL 仅存在于 `api/platform.ts`（本文件断言请求路径）；
 * - 身份头：rbac 域与 platform 新域走 `makeDomainJson` 域工厂，头随三员视角（hifi §5；
 *   P7 实测矩阵：sysadmin 看清单/监控/部署域、secadmin 授予收回、audadmin 留痕）；
 * - 处置参数边界（P4 实测）：`durationMinutes` 仅 SILENCE 传、其余不传（后端缺省 30，P4 实测
 *   0/1441 → 400 + 1013C0001；前端控件级约束在视图层，API 层零规则副本）；
 * - grant/revoke 成功出参 = 变更号字符串（E-2 勘误实测：幂等重放 = 空串）。
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

function apiFail(status: number, code: string, message: string): Response {
  const body = JSON.stringify({ code, message, data: null })
  return {
    ok: false,
    status,
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

describe('api/platform：rbac 域 4 函数（URL 单点 + 域头随视角）', () => {
  it('listRbacRoles：GET /api/v1/rbac/roles，系统管理员视角域头 platform.sysadmin', async () => {
    fetchMock.mockResolvedValue(apiOk([{ role: 'platform.sysadmin', permissions: ['platform.monitor.read'] }]))
    const roles = await listRbacRoles()
    const { path, init } = await lastCall()
    expect(path).toBe('/api/v1/rbac/roles')
    const headers = init.headers as Record<string, string>
    expect(headers['X-Ctds-Roles']).toBe('platform.sysadmin')
    expect(headers['X-Ctds-Subject']).toBe('S-admin-platform')
    expect(roles[0].role).toBe('platform.sysadmin')
  })

  it('grantPermission：POST 三元组请求体 + 变更人随 X-Ctds-Subject；成功出参 = 变更号字符串（E-2）', async () => {
    setAdminPersona('secadmin')
    fetchMock.mockResolvedValue(apiOk('RC7d724785ce1b4dbf'))
    const changeNo = await grantPermission({ role: 'user', permission: 'metering.read', changeOrderNo: 'CHG-001' })
    const { path, init } = await lastCall()
    expect(path).toBe('/api/v1/rbac/permissions/grant')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ role: 'user', permission: 'metering.read', changeOrderNo: 'CHG-001' })
    expect((init.headers as Record<string, string>)['X-Ctds-Roles']).toBe('platform.secadmin')
    expect(changeNo).toBe('RC7d724785ce1b4dbf')
  })

  it('revokePermission：POST 三元组请求体，安全管理员视角域头', async () => {
    setAdminPersona('secadmin')
    fetchMock.mockResolvedValue(apiOk('RC8a6768e4a19e4f6c'))
    await revokePermission({ role: 'user', permission: 'metering.read', changeOrderNo: 'CHG-002' })
    const { path, init } = await lastCall()
    expect(path).toBe('/api/v1/rbac/permissions/revoke')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ role: 'user', permission: 'metering.read', changeOrderNo: 'CHG-002' })
    expect((init.headers as Record<string, string>)['X-Ctds-Roles']).toBe('platform.secadmin')
  })

  it('listRbacChanges：pageQuery 复用拼接（DB-105：不建视图私有分页副本）', async () => {
    fetchMock.mockResolvedValue(apiOk({ list: [], total: 0, pageNum: 2, pageSize: 10, totalPages: 0 }))
    await listRbacChanges(2, 10)
    const { path } = await lastCall()
    expect(path).toBe('/api/v1/rbac/changes?pageNum=2&pageSize=10')
  })

  it('越权：403 envelope → ApiError 原文透传（P5 实测形态，不转译）', async () => {
    fetchMock.mockResolvedValue(apiFail(403, '1000C0005', '无权限执行该操作'))
    await expect(listRbacChanges(1, 10)).rejects.toMatchObject({
      code: '1000C0005',
      message: '无权限执行该操作',
    })
  })
})

describe('api/platform：三员视角域头矩阵（hifi §5 / P7 实测）', () => {
  it('视角 sysadmin：rbac 与 platform 两域头均为 platform.sysadmin', async () => {
    setAdminPersona('sysadmin')
    fetchMock.mockResolvedValue(apiOk({}))
    await listRbacRoles()
    await getMetrics()
    const { path, init } = await lastCall()
    expect(path).toBe('/api/v1/platform/metrics')
    expect((init.headers as Record<string, string>)['X-Ctds-Roles']).toBe('platform.sysadmin')
  })

  it('视角 audadmin：留痕域头 platform.audadmin（changes 仅审计管理员可读，P7 实测）', async () => {
    setAdminPersona('audadmin')
    fetchMock.mockResolvedValue(apiOk({ list: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 }))
    await listRbacChanges(1, 10)
    const { init } = await lastCall()
    expect((init.headers as Record<string, string>)['X-Ctds-Roles']).toBe('platform.audadmin')
  })

  it('视角 secadmin：授予收回域头 platform.secadmin（P7 实测 grant 仅安全管理员）', async () => {
    setAdminPersona('secadmin')
    fetchMock.mockResolvedValue(apiOk('RC1'))
    await grantPermission({ role: 'user', permission: 'greeting.read', changeOrderNo: 'CHG-003' })
    const { init } = await lastCall()
    expect((init.headers as Record<string, string>)['X-Ctds-Roles']).toBe('platform.secadmin')
  })

  it('普通主体档：rbac 与 platform 域头 = requester 单头（无管理角色，fail-closed 由服务端拒）', async () => {
    setActorMode('subject')
    fetchMock.mockResolvedValue(apiOk({}))
    await getMetrics()
    const { init } = await lastCall()
    const roles = (init.headers as Record<string, string>)['X-Ctds-Roles']
    expect(roles).toBe('requester')
    expect(roles).not.toContain('admin')
    expect(roles).not.toContain('platform.sysadmin')
  })
})

describe('api/platform：platform 域 4 函数（domain / metrics / alerts / actions）', () => {
  it('getDomain：GET /api/v1/platform/domain，sysadmin 视角域头', async () => {
    setAdminPersona('sysadmin')
    fetchMock.mockResolvedValue(apiOk({
      domainId: 'domain-01',
      domainName: '城市可信数据空间',
      platformVersion: 'V1.0',
      lifecycleStatus: 'ACTIVE',
      schemaList: [],
      serviceList: [],
      configConsistency: 'NOT_APPLICABLE',
      scopeNote: 'V1.0 = 单实例单部署域',
    }))
    const domain = await getDomain()
    const { path } = await lastCall()
    expect(path).toBe('/api/v1/platform/domain')
    expect(domain.lifecycleStatus).toBe('ACTIVE')
    expect(domain.scopeNote).toBe('V1.0 = 单实例单部署域')
  })

  it('getMetrics：GET /api/v1/platform/metrics', async () => {
    setAdminPersona('sysadmin')
    fetchMock.mockResolvedValue(apiOk({ services: [] }))
    const metrics = await getMetrics()
    const { path } = await lastCall()
    expect(path).toBe('/api/v1/platform/metrics')
    expect(metrics.services).toEqual([])
  })

  it('listAlerts：GET /api/v1/platform/alerts', async () => {
    setAdminPersona('sysadmin')
    fetchMock.mockResolvedValue(apiOk({ alerts: [] }))
    const alerts = await listAlerts()
    const { path } = await lastCall()
    expect(path).toBe('/api/v1/platform/alerts')
    expect(alerts.alerts).toEqual([])
  })

  it('disposeAlert 静默：请求体携带 durationMinutes（缺省 30 由后端兜，前端仅显式传值时携带）', async () => {
    setAdminPersona('sysadmin')
    fetchMock.mockResolvedValue(apiOk({ alertName: 'CtdsBackendDown', action: 'SILENCE', result: 'OK', silenceId: 'S-1', recordedAt: '2026-11-15T11:00:00' }))
    await disposeAlert({ alertName: 'CtdsBackendDown', action: 'SILENCE', note: '窗口期', durationMinutes: 60 })
    const { path, init } = await lastCall()
    expect(path).toBe('/api/v1/platform/alerts/actions')
    expect(init.method).toBe('POST')
    const body = JSON.parse(init.body as string) as Record<string, unknown>
    expect(body).toEqual({ alertName: 'CtdsBackendDown', action: 'SILENCE', note: '窗口期', durationMinutes: 60 })
  })

  it('disposeAlert 确认/升级：durationMinutes 不传（hifi §3：仅静默携带；越界最终裁决在后端）', async () => {
    setAdminPersona('sysadmin')
    fetchMock.mockResolvedValue(apiOk({ alertName: 'CtdsBackendDown', action: 'ACKNOWLEDGE', result: 'OK', silenceId: null, recordedAt: '2026-11-15T11:00:00' }))
    await disposeAlert({ alertName: 'CtdsBackendDown', action: 'ACKNOWLEDGE', durationMinutes: 60 })
    const { init } = await lastCall()
    const body = JSON.parse(init.body as string) as Record<string, unknown>
    expect(body['durationMinutes']).toBeUndefined()
    expect(body['action']).toBe('ACKNOWLEDGE')
  })

  it('处置失败：503 envelope → ApiError 原文（E-1 勘误实测形态：无监控栈 1013S0003）', async () => {
    setAdminPersona('sysadmin')
    fetchMock.mockResolvedValue(apiFail(503, '1013S0003', '监控数据源暂不可用，请稍后重试'))
    await expect(disposeAlert({ alertName: 'CtdsBackendDown', action: 'ESCALATE' })).rejects.toMatchObject({
      code: '1013S0003',
      message: '监控数据源暂不可用，请稍后重试',
    })
  })
})

describe('api/platform：evidence / connector 固定映射回归（DB-128，Q3-A 不随视角）', () => {
  it('evidence 域运营档 = platform.audadmin（DB-128 修复口径，不随三员视角变化）', async () => {
    const { evidenceRolesHeader } = await import('../stores/demoIdentity')
    setAdminPersona('secadmin')
    setActorMode('operator')
    expect(evidenceRolesHeader()).toBe('platform.audadmin')
    setAdminPersona('audadmin')
    expect(evidenceRolesHeader()).toBe('platform.audadmin')
  })

  it('connector 域运营档 = platform.secadmin,admin 组合（Q3-A 裁决：核验写面 + 既有读面，不随视角）', async () => {
    const { connectorRolesHeader } = await import('../stores/demoIdentity')
    setAdminPersona('audadmin')
    setActorMode('operator')
    expect(connectorRolesHeader()).toBe('platform.secadmin,admin')
    setAdminPersona('sysadmin')
    expect(connectorRolesHeader()).toBe('platform.secadmin,admin')
  })

  it('evidence / connector 普通档回归：requester / applicant 单头（既有锚点零破坏）', async () => {
    const { evidenceRolesHeader, connectorRolesHeader } = await import('../stores/demoIdentity')
    setActorMode('subject')
    expect(evidenceRolesHeader()).toBe('requester')
    expect(connectorRolesHeader()).toBe('applicant')
  })
})

describe('api/platform：类型契约（E-1 成功形状以后端代码为准）', () => {
  it('MetricsView 行形状：integrated/alive/note 字段齐备（MonitorViews.java 对账）', async () => {
    setAdminPersona('sysadmin')
    fetchMock.mockResolvedValue(apiOk({
      services: [{
        service: 'evidence-service',
        job: 'evidence',
        integrated: 'INTEGRATED',
        alive: 'UP',
        heapUsedBytes: 1,
        heapMaxBytes: 2,
        gcPauseSecondsRate5m: 0.01,
        httpRatePerSec1m: 3.2,
        errorRate5xx5m: 0,
        p95LatencyMs5m: 88,
        note: null,
      }],
    }))
    const metrics = await getMetrics()
    expect(metrics.services[0].integrated).toBe('INTEGRATED')
    expect(metrics.services[0].alive).toBe('UP')
  })

  it('AlertItemView 七要素 + stateNote（hifi §1.3 契约）', async () => {
    setAdminPersona('sysadmin')
    fetchMock.mockResolvedValue(apiOk({
      alerts: [{
        alertName: 'CtdsBackendDown',
        severity: 'critical',
        target: 'evidence-service',
        triggeredAt: '2026-11-15T11:00:00',
        currentValue: '0',
        threshold: '1',
        state: 'firing',
        stateNote: null,
      }],
    }))
    const alerts = await listAlerts()
    expect(alerts.alerts[0].state).toBe('firing')
    expect(alerts.alerts[0].alertName).toBe('CtdsBackendDown')
  })

  it('ApiError 为既有错误通道：code + message 保留（不新建第二份错误类型）', async () => {
    fetchMock.mockResolvedValue(apiFail(503, '1013S0002', '部署域配置与实际不一致，请联系系统管理员'))
    await expect(getDomain()).rejects.toBeInstanceOf(ApiError)
  })
})
