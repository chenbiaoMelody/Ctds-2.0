import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as spaceApi from './space'
import { setActorMode } from '../stores/demoIdentity'
import { setDemoRole } from '../stores/demoRole'
import { getDemoSubject, setDemoSubject } from './client'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../constants/space'

/**
 * 空间域 API 模块契约（WBS-3.2.6 hifi §2 / §4 + §7 T27）：
 * ① 端点函数面完整（25 个，与 hifi §1 端点表一一对应）；
 * ② **页面级角色头覆盖全局 `demoRolesHeader()`**：普通档 `applicant`（即便演示角色为 admin，
 *    全局头 `applicant,reviewer` 也不得出现）；运营档 `applicant,platform.operator`；
 * ③ 空主体编号 → 前置拦截（**零请求**），不发任何 HTTP。
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

describe('空间域 API 函数面（hifi §1 端点 1~25）', () => {
  it('导出全部 25 个端点函数', () => {
    const names = [
      'createSpace', 'enableSpace', 'freezeSpace', 'unfreezeSpace', 'dissolveSpace', 'updateSpace',
      'listSpaces', 'getSpace', 'applyAdmission', 'inviteMember', 'confirmAdmission', 'approveAdmission',
      'listAdmissions', 'listMyAdmissions', 'listMembers', 'assignRole', 'removeMember', 'leaveSpace',
      'transferOwnership', 'createPlatformPolicy', 'updatePlatformPolicy', 'listPlatformPolicies',
      'submitPolicyOverride', 'getEffectivePolicies', 'listActionLogs',
    ]
    for (const name of names) {
      expect(typeof (spaceApi as Record<string, unknown>)[name]).toBe('function')
    }
    expect(names.length).toBe(25)
  })

  it('端点路径与方法抽样：列表带分页参数、覆盖提交为 POST /policies/overrides、留痕为 GET /action-logs', async () => {
    await spaceApi.listSpaces(2, 20, '蓝')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/data-spaces?pageNum=2&pageSize=20&keyword=%E8%93%9D')
    expect(fetchMock.mock.calls[0][1].method).toBeUndefined()

    fetchMock.mockClear()
    await spaceApi.submitPolicyOverride(7, { entryKey: 'data.retention', entryValue: 'D180' })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/data-spaces/7/policies/overrides')
    expect(fetchMock.mock.calls[0][1].method).toBe('POST')
    expect(fetchMock.mock.calls[0][1].body).toBe(JSON.stringify({ entryKey: 'data.retention', entryValue: 'D180' }))

    fetchMock.mockClear()
    await spaceApi.listActionLogs(7, 1, 10)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/data-spaces/7/action-logs?pageNum=1&pageSize=10')

    fetchMock.mockClear()
    await spaceApi.transferOwnership(7, 12)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/data-spaces/7/ownership-transfer')
    expect(fetchMock.mock.calls[0][1].body).toBe(JSON.stringify({ targetMemberId: 12 }))
  })
})

describe('页面级角色头（T27：覆盖全局 demoRolesHeader）', () => {
  it('普通档：角色头 = applicant，且不含平台角色（即便演示角色为 admin）', async () => {
    setDemoRole('admin')
    setActorMode('subject')
    await spaceApi.listSpaces(1, 10)
    expect(firstHeaders()['X-Ctds-Roles']).toBe('applicant')
    expect(firstHeaders()['X-Ctds-Roles']).not.toContain('platform.operator')
    // 全局演示角色头（applicant,reviewer）不得残留在空间域请求上
    expect(firstHeaders()['X-Ctds-Roles']).not.toContain('reviewer')
  })

  it('运营档：角色头 = applicant,platform.operator（同一次调用内生效）', async () => {
    setActorMode('operator')
    await spaceApi.listMembers(1, 1, 10)
    expect(firstHeaders()['X-Ctds-Roles']).toBe('applicant,platform.operator')
  })

  it('主体头取自 client 的既有存储（不新建第二份 subject 存储）', async () => {
    // 经 client 的既有写入口设置（键 ctds-demo-subject），请求头的读取口径亦为同一份存储
    setDemoSubject('S20260925000001')
    await spaceApi.getSpace(3)
    expect(firstHeaders()['X-Ctds-Subject']).toBe('S20260925000001')
    expect(localStorage.getItem('ctds-demo-subject')).toBe('S20260925000001')
  })
})

describe('空主体前置拦截（T27：零请求）', () => {
  it('主体编号为空：不发起任何 HTTP 请求，直接以业务提示失败', async () => {
    mockedSubject.mockReturnValue('')
    await expect(spaceApi.listSpaces(1, 10)).rejects.toThrow(DEMO_SUBJECT_REQUIRED_TIP)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('主体编号为空白字符：同样零请求', async () => {
    mockedSubject.mockReturnValue('   ')
    await expect(spaceApi.createSpace({
      name: '空间', sceneType: 'OTHER', accessMode: 'OPEN', visibility: 'PUBLIC',
    })).rejects.toThrow(DEMO_SUBJECT_REQUIRED_TIP)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
