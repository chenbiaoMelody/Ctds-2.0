import { describe, it, expect, vi, afterEach } from 'vitest'
import { apiJson, apiUpload, ApiError, demoRolesHeader, getDemoSubject } from './client'
import { setDemoRole } from '../stores/demoRole'

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
