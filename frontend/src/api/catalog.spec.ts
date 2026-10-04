import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as catalogApi from './catalog'
import { catalogRolesHeader, setActorMode } from '../stores/demoIdentity'
import { setDemoRole } from '../stores/demoRole'
import { getDemoSubject, setDemoSubject } from './client'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../constants/space'

/**
 * 目录域 API 模块契约（WBS-3.3.6 hifi §1 / §2 / §4 + §7 T17）：
 * ① 端点函数面完整（29 个，与 hifi §2 命名清单一一对应：既有 26 端点中界面消费的 26 个
 *    + Q2-A 新增 R14/R15/R16 三个留痕读端点）；
 * ② **页面级角色头覆盖全局 `demoRolesHeader()`**：普通档 `provider`（**不得携带 `admin`**——
 *    即便演示角色为 admin，全局头 `applicant,reviewer` 也不得出现；T17 硬约束）；
 *    运营档 `admin`；
 * ③ 空主体编号 → 前置拦截（**零请求**），不发任何 HTTP（沿 3.2.6 E3 口径）。
 */
vi.mock('./client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./client')>()
  return { ...actual, getDemoSubject: vi.fn(actual.getDemoSubject) }
})

const mockedSubject = vi.mocked(getDemoSubject)
const fetchMock = vi.fn()

function okResponse(data: unknown) {
  return { json: async () => ({ code: '0', message: 'ok', data }) } as unknown as Response
}

function firstHeaders(): Record<string, string> {
  return fetchMock.mock.calls[0][1].headers as Record<string, string>
}

beforeEach(() => {
  localStorage.clear()
  mockedSubject.mockReturnValue('S20260925000001')
  fetchMock.mockReset()
  fetchMock.mockResolvedValue(okResponse({ list: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 }))
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('目录域 API 函数面（hifi §2 命名清单）', () => {
  it('导出全部 29 个端点函数', () => {
    const names = [
      'listCategories', 'searchProducts', 'getProductDetail', 'getProductChangeLogs',
      'listFavorites', 'favoriteProduct', 'unfavoriteProduct', 'listSubscriptions',
      'subscribeProduct', 'unsubscribeProduct',
      'registerDataset', 'listMyDatasets', 'getDataset', 'updateDataset', 'cancelDataset',
      'listVocabularies', 'listTerms',
      'createProduct', 'updateProduct', 'publishProduct', 'delistProduct', 'cancelProduct',
      'listMyProducts', 'forceDelistProduct', 'getProductGovernance', 'getDatasetGovernance',
      'listDatasetActionLogs', 'listProductActionLogs', 'listInteractionLogs',
    ]
    for (const name of names) {
      expect(typeof (catalogApi as Record<string, unknown>)[name]).toBe('function')
    }
    expect(names.length).toBe(29)
  })

  it('端点路径与方法抽样：检索带分页参数、登记走空间域路径段、三个留痕读端点路径正确', async () => {
    await catalogApi.searchProducts(2, 20, '普惠', 'finance')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/data-products?pageNum=2&pageSize=20&keyword=%E6%99%AE%E6%83%A0&categoryCode=finance')
    expect(fetchMock.mock.calls[0][1].method).toBeUndefined()

    fetchMock.mockClear()
    await catalogApi.registerDataset(29, {
      name: '普惠金融数据集', type: 'DATASET', intro: '简介', tags: ['金融'],
      declareCategory: '金融', declareLevel: 'L2', declareImportant: false,
    })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/data-spaces/29/datasets')
    expect(fetchMock.mock.calls[0][1].method).toBe('POST')
    expect(fetchMock.mock.calls[0][1].body).toBe(JSON.stringify({
      name: '普惠金融数据集', type: 'DATASET', intro: '简介', tags: ['金融'],
      declareCategory: '金融', declareLevel: 'L2', declareImportant: false,
    }))

    fetchMock.mockClear()
    await catalogApi.listDatasetActionLogs(9, 1, 10)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/datasets/9/action-logs?pageNum=1&pageSize=10')

    fetchMock.mockClear()
    await catalogApi.listProductActionLogs(7, 1, 10)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/data-products/7/action-logs?pageNum=1&pageSize=10')

    fetchMock.mockClear()
    await catalogApi.listInteractionLogs(1, 10)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/catalog/interaction-logs?pageNum=1&pageSize=10')

    fetchMock.mockClear()
    await catalogApi.cancelDataset(9)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/datasets/9/cancellation')
    expect(fetchMock.mock.calls[0][1].method).toBe('POST')
    expect(fetchMock.mock.calls[0][1].body).toBe(JSON.stringify({ confirmCancellation: true }))

    fetchMock.mockClear()
    await catalogApi.forceDelistProduct(7, '违规内容')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/data-products/7/force-delist')
    expect(fetchMock.mock.calls[0][1].body).toBe(JSON.stringify({ forceReason: '违规内容' }))
  })
})

describe('页面级角色头（T17：覆盖全局 demoRolesHeader）', () => {
  it('普通档：角色头 = provider，且不得携带 admin（T17 硬约束；即便演示角色为 admin）', async () => {
    setDemoRole('admin')
    setActorMode('subject')
    await catalogApi.listCategories()
    expect(firstHeaders()['X-Ctds-Roles']).toBe('provider')
    // T17 硬约束：普通档携带 admin 会让治理类与"越权被拒"类剧本步骤被服务端放行
    expect(firstHeaders()['X-Ctds-Roles']).not.toContain('admin')
    // 全局演示角色头（applicant,reviewer）不得残留在目录域请求上
    expect(firstHeaders()['X-Ctds-Roles']).not.toContain('reviewer')
    expect(firstHeaders()['X-Ctds-Roles']).not.toContain('applicant')
  })

  it('运营档：角色头 = admin（治理例外读面与治理动作）', async () => {
    setActorMode('operator')
    await catalogApi.getProductGovernance(7)
    expect(firstHeaders()['X-Ctds-Roles']).toBe('admin')
  })

  it('catalogRolesHeader 为单点函数：普通档 provider / 运营档 admin（不读演示角色）', () => {
    setDemoRole('admin')
    setActorMode('subject')
    expect(catalogRolesHeader()).toBe('provider')
    setActorMode('operator')
    expect(catalogRolesHeader()).toBe('admin')
  })

  it('主体头取自 client 的既有存储（不新建第二份 subject 存储）', async () => {
    setDemoSubject('S20260925000001')
    await catalogApi.listMyDatasets(1, 10)
    expect(firstHeaders()['X-Ctds-Subject']).toBe('S20260925000001')
    expect(localStorage.getItem('ctds-demo-subject')).toBe('S20260925000001')
  })
})

describe('空主体前置拦截（T17：零请求）', () => {
  it('主体编号为空：不发起任何 HTTP 请求，直接以业务提示失败', async () => {
    mockedSubject.mockReturnValue('')
    await expect(catalogApi.listMyDatasets(1, 10)).rejects.toThrow(DEMO_SUBJECT_REQUIRED_TIP)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('主体编号为空白字符：同样零请求', async () => {
    mockedSubject.mockReturnValue('   ')
    await expect(catalogApi.searchProducts(1, 10)).rejects.toThrow(DEMO_SUBJECT_REQUIRED_TIP)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
