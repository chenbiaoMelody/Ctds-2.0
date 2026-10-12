import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  apiJson,
  apiUpload,
  ApiError,
  demoRolesHeader,
  getDemoSubject,
  makeDomainJson,
  pageQuery,
  setDemoSubject,
} from './client'
import { setDemoRole } from '../stores/demoRole'
// DB-41 评审修复：t4 编码锚改为触达产品代码（域函数产出的请求 URL），不再只断言 JS 内置函数
import { fetchSubjectDetail } from './subject'
import { resolveDid } from './did'
import {
  catalogRolesHeader,
  contractRolesHeader,
  setActorMode,
  spaceRolesHeader,
} from '../stores/demoIdentity'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../constants/space'

/**
 * WBS-3.1.5 演示期 API 封装测试：身份头注入 / ApiResult 解包 / 错误码保留。
 * fetch 用 vi.stubGlobal 替换，unstubAllGlobals 防泄漏（工具链坑记忆口径）。
 */
const stubFetch = (payload: unknown) =>
  vi.fn(() =>
    Promise.resolve({
      json: () => Promise.resolve(payload),
    }),
  ) as unknown as typeof fetch

describe('api/client', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('成功响应解包返回 data', async () => {
    vi.stubGlobal('fetch', stubFetch({ code: '0', message: 'OK', data: { subjectNo: 'S1' } }))
    await expect(apiJson('/api/x')).resolves.toEqual({ subjectNo: 'S1' })
  })

  it('业务错误抛 ApiError 并保留九位错误码与文案', async () => {
    vi.stubGlobal('fetch', stubFetch({ code: '1004C0002', message: '当前状态不允许执行审核操作', data: null }))
    const promise = apiJson('/api/x')
    await expect(promise).rejects.toBeInstanceOf(ApiError)
    await promise.catch((error: ApiError) => {
      expect(error.code).toBe('1004C0002')
      expect(error.message).toBe('当前状态不允许执行审核操作')
    })
  })

  it('附加演示身份头；admin 兼任审核员 roles 含双角色', async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ code: '0', data: null }) }),
    )
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)
    setDemoRole('admin')
    await apiJson('/api/x')
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    const headers = init.headers as Record<string, string>
    expect(headers['X-Ctds-Roles']).toBe('applicant,reviewer')
    expect(headers['X-Ctds-Subject']).toBe(getDemoSubject())
    expect(demoRolesHeader()).toBe('applicant,reviewer')
  })

  it('普通用户 roles 仅 applicant', () => {
    setDemoRole('user')
    expect(demoRolesHeader()).toBe('applicant')
  })

  it('apiUpload 以 multipart FormData 携带文件与身份头', async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ code: '0', data: { fileName: 'A1.jpg' } }) }),
    )
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)
    const file = new File(['image'], 'A1.jpg')
    await expect(apiUpload('/api/upload', file)).resolves.toEqual({ fileName: 'A1.jpg' })
    const [path, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(path).toBe('/api/upload')
    expect(init.body).toBeInstanceOf(FormData)
    const headers = init.headers as Record<string, string>
    expect(headers['X-Ctds-Subject']).toBe(getDemoSubject())
  })
})

/**
 * DB-41 公用横切四族收敛（hifi `docs/designs/DB-41-hifi.md` §5.1 t1~t5）：
 * 公用层新增面 = `pageQuery`（唯一副本）+ `makeDomainJson`（域请求工厂），
 * 契约 = 空主体前置拦截（零请求）/ 角色头合并优先级 / 编码 no-op 锚 / 三域导出面不变。
 * 三域 api spec 的 URL 断言零改动即零行为变更实证（§5.2 回归锚），此处只锚公用层语义。
 */
const captureFetch = () => vi.fn(() =>
  Promise.resolve({ json: () => Promise.resolve({ code: '0', message: 'OK', data: null }) }),
)
const firstHeaders = (mock: ReturnType<typeof captureFetch>): Record<string, string> =>
  (mock.mock.calls[0] as unknown as [string, RequestInit])[1].headers as Record<string, string>

describe('DB-41 公用层（pageQuery + 域请求工厂）', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('t1 域请求工厂：空主体编号 → 前置拦截零请求（1000C0002 + 统一文案）', async () => {
    const fetchMock = captureFetch()
    vi.stubGlobal('fetch', fetchMock)
    setDemoSubject('   ')
    const domainJson = makeDomainJson(() => 'provider')
    await expect(domainJson('/api/x')).rejects.toBeInstanceOf(ApiError)
    await expect(domainJson('/api/x')).rejects.toMatchObject({
      code: '1000C0002',
      message: DEMO_SUBJECT_REQUIRED_TIP,
    } satisfies Partial<ApiError>)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('t2 域请求工厂：附页面级角色头，调用方显式同名头覆盖（合并顺序与现状一致）', async () => {
    const fetchMock = captureFetch()
    vi.stubGlobal('fetch', fetchMock)
    setDemoSubject('S20260925000001')
    const domainJson = makeDomainJson(() => 'provider')
    await domainJson('/api/x')
    expect(firstHeaders(fetchMock)['X-Ctds-Roles']).toBe('provider')
    expect(firstHeaders(fetchMock)['X-Ctds-Subject']).toBe('S20260925000001')
    await domainJson('/api/x', { headers: { 'X-Ctds-Roles': 'admin' } })
    const second = (fetchMock.mock.calls[1] as unknown as [string, RequestInit])[1]
    expect((second.headers as Record<string, string>)['X-Ctds-Roles']).toBe('admin')
    // 覆盖只作用于同名头：其余合并头（主体头）不受影响
    expect((second.headers as Record<string, string>)['X-Ctds-Subject']).toBe('S20260925000001')
  })

  it('t3 pageQuery：两参基础形态 / extra 过滤项追加 / 空 extra 不产生多余参数', () => {
    expect(pageQuery(1, 10)).toBe('?pageNum=1&pageSize=10')
    expect(pageQuery(1, 10, {})).toBe('?pageNum=1&pageSize=10')
    expect(pageQuery(2, 20, { keyword: '普惠' }))
      .toBe('?pageNum=2&pageSize=20&keyword=%E6%99%AE%E6%83%A0')
    // 边界与转义：零值按数值序列化；过滤项值经 URLSearchParams 编码（空格 → +）
    expect(pageQuery(0, 0)).toBe('?pageNum=0&pageSize=0')
    expect(pageQuery(1, 10, { keyword: 'a b', status: 'A/B' }))
      .toBe('?pageNum=1&pageSize=10&keyword=a+b&status=A%2FB')
  })

  it('t4 编码锚：域函数产出的请求 URL 中原编号逐位不变、含特殊字符取值确实转义（守卫锚，防未来"优化"掉编码）', async () => {
    const fetchMock = captureFetch()
    vi.stubGlobal('fetch', fetchMock)
    const calledUrl = (i = 0): string => (fetchMock.mock.calls[i] as unknown as [string, RequestInit])[0]
    // 现行编号（字母数字）经 encodeURIComponent 为 no-op → URL 逐位不变（subject ①族补编点）
    await fetchSubjectDetail('S20260925000001')
    expect(calledUrl()).toBe('/api/v1/subject/registrations/S20260925000001')
    // did 域既有编码点：含 ":" 的 DID 保持既有转义形态
    await resolveDid('did:ctds:S1')
    expect(calledUrl(1)).toBe('/api/v1/did/did%3Actds%3AS1')
    // 口径有效性：含特殊字符的取值确实被转义（一旦编码被"优化"掉，本断言即红）
    await fetchSubjectDetail('S 2026/001')
    expect(calledUrl(2)).toBe('/api/v1/subject/registrations/S%202026%2F001')
  })

  it('t5 三域 rolesHeader 导出面：运营档 / 普通档各自取值不变', () => {
    setActorMode('operator')
    expect(spaceRolesHeader()).toBe('applicant,platform.operator')
    expect(catalogRolesHeader()).toBe('admin')
    expect(contractRolesHeader()).toBe('admin')
    setActorMode('subject')
    expect(spaceRolesHeader()).toBe('applicant')
    expect(catalogRolesHeader()).toBe('provider')
    expect(contractRolesHeader()).toBe('provider')
  })
})
