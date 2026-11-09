import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  auditAnomalies,
  auditOverview,
  auditTrails,
  downloadAuditReport,
  generateReport,
  getRecordDetail,
  listRecords,
  verifyRecord,
} from './evidence'
import { ApiError, setDemoSubject } from './client'
import { setActorMode } from '../stores/demoIdentity'
import type { EvidenceRecordView } from './evidence'

/**
 * 存证域 API 单元（WBS-3.8.5 hifi §3 / §8 T14；DB-41 / DB-105 口径）：
 * - URL 单点：全部端点 URL 仅存在于 `api/evidence.ts`（本文件断言请求路径与拼接）；
 * - 身份头：统一 `makeDomainJson(evidenceRolesHeader())`——运营档 admin / 普通档 requester
 *   （前置检查 P5 实测裁定，剧本 C-8.2 普通档身份示例单头一字不差）；
 * - 分页：复用共享 `pageQuery`（DB-105：不建视图私有副本）；
 * - `downloadAuditReport`：裸 fetch 豁免 №2——成功 = Content-Disposition 文件名解析 + Blob；
 *   失败 = ApiResult JSON → ApiError 原文透传（前置检查 P8 实证失败形态：HTTP 400 +
 *   1000C0003"记录不存在"，hifi §1.2 草案 500+1012S0003 为完整性校验形态、不可构造）。
 */

type FetchCall = { path: string; init: RequestInit }

const fetchMock = vi.fn()

/** 组装 ApiResult JSON 响应（plain object，零环境依赖）。 */
function apiOk(data: unknown): Response {
  const body = JSON.stringify({ code: '0', message: 'success', data })
  return {
    ok: true,
    status: 200,
    json: async () => JSON.parse(body),
  } as unknown as Response
}

/** 组装 ApiResult 错误响应（HTTP 状态 + 业务码原文，P8 实证形态）。 */
function apiFail(status: number, code: string, message: string): Response {
  const body = JSON.stringify({ code, message, data: null })
  return {
    ok: false,
    status,
    json: async () => JSON.parse(body),
  } as unknown as Response
}

/** 文件下载成功响应（Content-Disposition + blob）。 */
function fileResponse(disposition: string): Response {
  return {
    ok: true,
    status: 200,
    headers: { get: (name: string) => (name.toLowerCase() === 'content-disposition' ? disposition : null) },
    blob: async () => new Blob(['csv-data'], { type: 'text/csv' }),
  } as unknown as Response
}

async function lastCall(): Promise<FetchCall> {
  expect(fetchMock).toHaveBeenCalled()
  const calls = fetchMock.mock.calls as unknown as [string, RequestInit][]
  const [path, init] = calls[calls.length - 1]
  return { path, init: init ?? {} }
}

function record(over: Partial<EvidenceRecordView> = {}): EvidenceRecordView {
  return {
    evidenceNo: 'EV20261107000004',
    stageType: 'DELIVERY',
    businessNo: 'DL000031',
    operationType: 'DELIVERY_CREATED',
    subjectDidDigest: '0e23678330996e0a0933c1ac7c9af5ad160251db1a18b33ec2e4b85fbd9fc46c',
    occurredAt: '2026-11-07T15:02:33.000Z',
    contentDigest: '58a3eb769af998155b7aa1ca2bbb137a7de9948bacb472a88c9a785fefd0a69b',
    status: 'ON_CHAIN',
    chainTxId: 'LTX000000000004',
    chainBlockNo: 4,
    retryCount: 0,
    lastErrorSummary: null,
    createdAt: '2026-11-07T15:02:33.419Z',
    updatedAt: '2026-11-07T15:02:33.468Z',
    ...over,
  }
}

beforeEach(() => {
  localStorage.clear()
  setDemoSubject('S-admin-evidence')
  setActorMode('operator')
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('T14 api/evidence：治理读面 4 函数', () => {
  it('listRecords：pageQuery 拼接 + filters 空值剔除 + 运营档身份头 admin', async () => {
    fetchMock.mockResolvedValue(apiOk({ list: [record()], total: 1, pageNum: 1, pageSize: 10, totalPages: 1 }))
    const page = await listRecords({
      pageNum: 1,
      pageSize: 10,
      stageType: 'DELIVERY',
      businessNo: '',
      status: undefined,
    })
    const { path, init } = await lastCall()
    expect(path).toBe('/api/v1/evidence/records?pageNum=1&pageSize=10&stageType=DELIVERY')
    expect(init.method).toBeUndefined()
    const headers = init.headers as Record<string, string>
    expect(headers['X-Ctds-Roles']).toBe('admin')
    expect(headers['X-Ctds-Subject']).toBe('S-admin-evidence')
    expect(page.list[0].evidenceNo).toBe('EV20261107000004')
  })

  it('普通档（subject 模式）身份头 = requester 单头（P5 实测裁定；fail-closed 由服务端拒）', async () => {
    setActorMode('subject')
    fetchMock.mockResolvedValue(apiOk({ list: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 }))
    await listRecords({ pageNum: 1, pageSize: 10, stageType: 'DELIVERY' })
    const { init } = await lastCall()
    expect((init.headers as Record<string, string>)['X-Ctds-Roles']).toBe('requester')
  })

  it('listRecords 五维全参拼接：status + from + to 进查询串（评审④ F-2 补齐）', async () => {
    fetchMock.mockResolvedValue(apiOk({ list: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 }))
    await listRecords({
      pageNum: 1,
      pageSize: 10,
      stageType: 'USAGE',
      businessNo: 'USG000000000569',
      status: 'FAILED',
      from: '2026-10-01T00:00:00Z',
      to: '2026-11-08T00:00:00Z',
    })
    const { path } = await lastCall()
    expect(path).toBe(
      '/api/v1/evidence/records?pageNum=1&pageSize=10&stageType=USAGE&businessNo=USG000000000569&status=FAILED&from=2026-10-01T00%3A00%3A00Z&to=2026-11-08T00%3A00%3A00Z',
    )
  })

  it('getRecordDetail：编号 encodeURIComponent + EvidenceDetailView 透传', async () => {
    fetchMock.mockResolvedValue(apiOk({ record: record(), actions: [] }))
    const detail = await getRecordDetail('EV20261107000004')
    const { path } = await lastCall()
    expect(path).toBe('/api/v1/evidence/records/EV20261107000004')
    expect(detail.record.status).toBe('ON_CHAIN')
  })

  it('verifyRecord：POST /records/{no}/verify（HTTP 恒 200，结论不映射错误）', async () => {
    fetchMock.mockResolvedValue(
      apiOk({
        conclusion: 'PASS',
        evidenceNo: 'EV20261107000004',
        businessNo: 'DL000031',
        status: 'ON_CHAIN',
        expectedDigest: '58a3',
        actualDigest: '58a3',
        localDigest: '58a3',
        chainTxId: 'LTX000000000004',
        verifiedBy: 'S-admin-evidence',
        verifiedAt: '2026-11-08T03:01:35.184Z',
      }),
    )
    const view = await verifyRecord('EV20261107000004')
    const { path, init } = await lastCall()
    expect(path).toBe('/api/v1/evidence/records/EV20261107000004/verify')
    expect(init.method).toBe('POST')
    expect(view.conclusion).toBe('PASS')
  })
})

describe('T14 api/evidence：监管审计读面（E1~E4）', () => {
  it('auditOverview：无参无查询串；带参 from/to 进查询串（ISO-8601 透传）', async () => {
    fetchMock.mockResolvedValue(apiOk({ matrix: {}, anomalies: {}, snapshotAt: '2026-11-08T02:59:19.419Z' }))
    await auditOverview()
    const { path: bare } = await lastCall()
    expect(bare).toBe('/api/v1/evidence/audit/overview')

    await auditOverview('2026-10-01T00:00:00Z', '2026-11-08T00:00:00Z')
    const { path: withRange } = await lastCall()
    expect(withRange).toBe(
      '/api/v1/evidence/audit/overview?from=2026-10-01T00%3A00%3A00Z&to=2026-11-08T00%3A00%3A00Z',
    )
  })

  it('auditAnomalies：分页 + from/to 过滤拼接（E2 出参 18 字段类型透传）', async () => {
    fetchMock.mockResolvedValue(apiOk({ list: [], total: 0, pageNum: 1, pageSize: 5, totalPages: 0 }))
    await auditAnomalies({ pageNum: 2, pageSize: 5, from: '2026-10-01T00:00:00Z' })
    const { path } = await lastCall()
    expect(path).toBe('/api/v1/evidence/audit/anomalies?pageNum=2&pageSize=5&from=2026-10-01T00%3A00%3A00Z')
  })

  it('auditTrails：双模同端点——businessNo（TRAIL）与条件组（LIST）均按 filters 拼接', async () => {
    fetchMock.mockResolvedValue(apiOk({ mode: 'TRAIL' }))
    await auditTrails({ businessNo: 'DL000031' })
    const { path: trail } = await lastCall()
    expect(trail).toBe('/api/v1/evidence/audit/trails?businessNo=DL000031')

    fetchMock.mockResolvedValue(apiOk({ mode: 'LIST' }))
    await auditTrails({ stageType: 'DELIVERY', pageNum: 1, pageSize: 10 })
    const { path: list } = await lastCall()
    expect(list).toBe('/api/v1/evidence/audit/trails?stageType=DELIVERY&pageNum=1&pageSize=10')
  })

  it('generateReport：POST /audit/reports + body JSON（条件 ≥1 由后端裁决）', async () => {
    fetchMock.mockResolvedValue(
      apiOk({
        reportNo: 'EV-RPT-20261108-0002',
        scope: { stageType: 'DELIVERY' },
        recordCount: 1,
        conclusionSummary: { PASS: 1, MISMATCH: 0, NOT_ON_CHAIN: 0, NOT_VERIFIED: 0 },
        anomalyCount: 0,
        contentDigest: 'ca85',
        remark: 'probe',
        generatedBy: 'S-admin-evidence',
        generatedAt: '2026-11-08T03:02:09.080Z',
      }),
    )
    const view = await generateReport({ stageType: 'DELIVERY', remark: 'probe' })
    const { path, init } = await lastCall()
    expect(path).toBe('/api/v1/evidence/audit/reports')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ stageType: 'DELIVERY', remark: 'probe' })
    expect(view.conclusionSummary).toHaveProperty('NOT_VERIFIED')
  })
})

describe('T14 api/evidence：错误解包（ApiError 原文透传）', () => {
  it('code != 0 → 抛 ApiError（code/message 原样，前端零转译）', async () => {
    fetchMock.mockResolvedValue(apiFail(400, '1012C0002', '至少需要一个过滤条件'))
    await expect(listRecords({ pageNum: 1, pageSize: 10 })).rejects.toMatchObject({
      code: '1012C0002',
      message: '至少需要一个过滤条件',
    })
  })

  it('审计面越权同码同文（400 + 1000C0003"记录不存在"）原样抛出，不做存在性分支', async () => {
    fetchMock.mockResolvedValue(apiFail(400, '1000C0003', '记录不存在'))
    await expect(auditOverview()).rejects.toBeInstanceOf(ApiError)
    await expect(auditOverview()).rejects.toMatchObject({ code: '1000C0003', message: '记录不存在' })
  })
})

describe('T14 api/evidence：downloadAuditReport（裸 fetch 豁免 №2）', () => {
  it('成功：format=csv 进查询串 + plain filename 解析 + Blob 返回 + 双身份头', async () => {
    fetchMock.mockResolvedValue(fileResponse('attachment; filename="EV-RPT-20261108-0002.csv"'))
    const out = await downloadAuditReport('EV-RPT-20261108-0002', 'csv')
    const { path, init } = await lastCall()
    expect(path).toBe('/api/v1/evidence/audit/reports/EV-RPT-20261108-0002/export?format=csv')
    const headers = init.headers as Record<string, string>
    expect(headers['X-Ctds-Roles']).toBe('admin')
    expect(headers['X-Ctds-Subject']).toBe('S-admin-evidence')
    expect(out.fileName).toBe('EV-RPT-20261108-0002.csv')
    expect(out.blob).toBeInstanceOf(Blob)
  })

  it("成功：UTF-8 文件名分支（filename*=UTF-8'' 优先于 plain filename）", async () => {
    fetchMock.mockResolvedValue(
      fileResponse("attachment; filename=\"report.csv\"; filename*=UTF-8''%E5%AE%A1%E8%AE%A1%E6%8A%A5%E5%91%8A.csv"),
    )
    const out = await downloadAuditReport('EV-RPT-20261108-0002', 'json')
    const { path } = await lastCall()
    expect(path).toBe('/api/v1/evidence/audit/reports/EV-RPT-20261108-0002/export?format=json')
    expect(out.fileName).toBe('审计报告.csv')
  })

  it('失败：HTTP 400 + 1000C0003"记录不存在"（P8 实证形态）→ ApiError 原文抛出', async () => {
    fetchMock.mockResolvedValue(apiFail(400, '1000C0003', '记录不存在'))
    await expect(downloadAuditReport('EV-RPT-20990101-9999', 'csv')).rejects.toMatchObject({
      code: '1000C0003',
      message: '记录不存在',
    })
  })
})
