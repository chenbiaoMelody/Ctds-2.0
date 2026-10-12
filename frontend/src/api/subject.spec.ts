import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as subjectApi from './subject'
import { setDemoSubject } from './client'

/**
 * 主体域 API 路径段编码锚（DB-41 ① 族：subject 9 处补编 + 进度查询既有编码）。
 *
 * 评审④ findings 修复：① 族原仅以"跑完即删"的临时核验用例佐证，仓内无持久可证伪锚点；
 * 本 spec 将其固化为持久回归锚——编码被移除或口径漂移即红（零行为变更锚 hifi §4.1）。
 */
const fetchMock = vi.fn()

function okResponse(data: unknown) {
  return { json: async () => ({ code: '0', message: 'ok', data }) } as unknown as Response
}

function calledUrl(index = 0): string {
  return fetchMock.mock.calls[index][0] as string
}

beforeEach(() => {
  localStorage.clear()
  setDemoSubject('S20260925000001')
  fetchMock.mockReset()
  fetchMock.mockResolvedValue(okResponse({ list: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 }))
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('主体域 API 路径段编码（DB-41 ① 族持久锚点）', () => {
  it('9 处补编函数：现行编号（字母数字）编码为 no-op → URL 逐位不变', async () => {
    const no = 'S20260925000001'

    await subjectApi.fetchSubjectDetail(no)
    expect(calledUrl()).toBe(`/api/v1/subject/registrations/${no}`)

    fetchMock.mockClear()
    await subjectApi.fetchProfile(no)
    expect(calledUrl()).toBe(`/api/v1/subject/registrations/${no}/certification`)

    fetchMock.mockClear()
    await subjectApi.uploadLicense(no, new File(['image'], 'A1.jpg'))
    expect(calledUrl()).toBe(`/api/v1/subject/registrations/${no}/certification/license`)

    fetchMock.mockClear()
    await subjectApi.confirmLicense(no, {
      subjectName: '蓝天数据科技有限公司',
      uscc: '91330100MA27XW123X',
      legalPerson: '张三',
      regAddress: '浙江省杭州市',
    })
    expect(calledUrl()).toBe(`/api/v1/subject/registrations/${no}/certification/license/confirmation`)

    fetchMock.mockClear()
    await subjectApi.verifyLegalPerson(no, { legalPersonName: '张三', legalPersonIdNo: '330100199001010011' })
    expect(calledUrl()).toBe(`/api/v1/subject/registrations/${no}/certification/legal-person-verifications`)

    fetchMock.mockClear()
    await subjectApi.submitGovCertificate(no, new File(['image'], 'A1.jpg'))
    expect(calledUrl()).toBe(`/api/v1/subject/registrations/${no}/certification/gov-ca-certificate`)

    fetchMock.mockClear()
    await subjectApi.fetchLicenseImage(no)
    expect(calledUrl()).toBe(`/api/v1/subject/registrations/${no}/certification/license/image`)

    fetchMock.mockClear()
    await subjectApi.approveSubject(no)
    expect(calledUrl()).toBe(`/api/v1/subject/registrations/${no}/review/approval`)

    fetchMock.mockClear()
    await subjectApi.rejectSubject(no, '材料不齐')
    expect(calledUrl()).toBe(`/api/v1/subject/registrations/${no}/review/rejection`)
  })

  it('含特殊字符的编号确实转义（口径有效性：编码被"优化"掉即红）', async () => {
    await subjectApi.fetchSubjectDetail('S 2026/001')
    expect(calledUrl()).toBe('/api/v1/subject/registrations/S%202026%2F001')

    fetchMock.mockClear()
    await subjectApi.approveSubject('S1:2')
    expect(calledUrl()).toBe('/api/v1/subject/registrations/S1%3A2/review/approval')
  })

  it('进度自助查询：subjectNo 编码 + uscc 查询值经 URLSearchParams 编码', async () => {
    await subjectApi.fetchRegistrationProgress('S20260925000001', '91330100MA27XW123X')
    expect(calledUrl()).toBe('/api/v1/subject/registrations/S20260925000001/progress?uscc=91330100MA27XW123X')

    fetchMock.mockClear()
    await subjectApi.fetchRegistrationProgress('S1 2', 'A B/C')
    expect(calledUrl()).toBe('/api/v1/subject/registrations/S1%202/progress?uscc=A+B%2FC')
  })

  it('审核队列：单对象分页签名 + pageQuery 唯一副本串（URL 逐位不变）', async () => {
    await subjectApi.fetchReviewQueue({ pageNum: 2, pageSize: 20 })
    expect(calledUrl()).toBe('/api/v1/subject/review/queue?pageNum=2&pageSize=20')
  })

  it('有意不收敛点：本域维持"无空主体前置拦截"现状（hifi §4.2，接入工厂必红）', async () => {
    setDemoSubject('   ')
    await subjectApi.fetchSubjectDetail('S1')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(calledUrl()).toBe('/api/v1/subject/registrations/S1')
  })
})
