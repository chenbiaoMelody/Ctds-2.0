import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import CertificationView from './CertificationView.vue'
import { fetchProfile, fetchSubjectDetail, verifyLegalPerson } from '../../api/subject'

/**
 * WBS-3.1.5 申请人侧认证页测试：企业/政务流程分区互斥（行为 6"全程不出现"）、
 * 驳回理由可见（行为 4 验收-2）、状态徽标渲染。
 * WBS-3.1.6 F2 增量：OCR 四要素以"值"回填核对表单（非仅分区出现）、法人核验发起链路
 * （payload 透传 + 结论提示 + 当日剩余次数）、次数用完业务文案如实展示（行为 3 验收-3 前端侧）。
 */
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { subjectNo: 'S20260913000002' } }),
}))

vi.mock('../../api/subject', () => ({
  fetchProfile: vi.fn(),
  fetchSubjectDetail: vi.fn(),
  uploadLicense: vi.fn(),
  confirmLicense: vi.fn(),
  verifyLegalPerson: vi.fn(),
  submitGovCertificate: vi.fn(),
}))

const mockedProfile = vi.mocked(fetchProfile)
const mockedDetail = vi.mocked(fetchSubjectDetail)
const mockedVerify = vi.mocked(verifyLegalPerson)

const baseDetail = (subjectType: string) => ({
  subjectNo: 'S20260913000002',
  subjectName: '市大数据管理局',
  uscc: '11330100MA27XW1301',
  subjectType,
  regAddress: '杭州市XX区',
  contactName: '王科',
  contactPhone: '138****5678',
  status: 'PENDING_REVIEW' as const,
  statusLogs: [],
})

const baseProfile = (overrides: Record<string, unknown>) => ({
  subjectNo: 'S20260913000002',
  status: 'PENDING_REVIEW',
  license: null,
  govCa: null,
  verifications: [],
  remainingAttemptsToday: null,
  ...overrides,
})

const mountPage = async () => {
  const wrapper = mount(CertificationView, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  return wrapper
}

describe('认证与档案页（WBS-3.1.5 界面化）', () => {
  it('政务主体：显示政务 CA 证书上传区，不显示执照与法人核验区', async () => {
    mockedProfile.mockResolvedValue(
      baseProfile({
        govCa: { uploaded: false, fileName: '', lastConclusion: null, lastFailReason: null, lastSubmittedAt: null },
      }) as never,
    )
    mockedDetail.mockResolvedValue(baseDetail('GOV') as never)
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('政务 CA 数字证书')
    expect(wrapper.text()).not.toContain('营业执照上传')
    expect(wrapper.text()).not.toContain('法人实人核验')
  })

  it('企业主体：显示执照上传、核对确认与法人核验流程区', async () => {
    mockedProfile.mockResolvedValue(
      baseProfile({
        license: {
          uploaded: true,
          recognizable: true,
          confirmed: true,
          confirmedAt: '2026-09-13T10:00:00',
          confirmedResult: { subjectName: '蓝天数据科技有限公司', uscc: '91330100MA27XW123X', legalPerson: '张伟', regAddress: '杭州市XX区' },
        },
        verifications: [],
        remainingAttemptsToday: 5,
      }) as never,
    )
    mockedDetail.mockResolvedValue(baseDetail('ENTERPRISE') as never)
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('营业执照上传')
    expect(wrapper.text()).toContain('核对确认')
    expect(wrapper.text()).toContain('法人实人核验')
    expect(wrapper.text()).toContain('当日剩余核验次数')
  })

  it('已驳回主体：展示驳回理由（行为 4 验收-2 界面兑现）', async () => {
    mockedProfile.mockResolvedValue(baseProfile({ status: 'REJECTED' }) as never)
    mockedDetail.mockResolvedValue({
      ...baseDetail('GOV'),
      statusLogs: [
        {
          fromStatus: 'PENDING_REVIEW',
          toStatus: 'REJECTED',
          triggerRole: 'REVIEWER',
          operator: 'reviewer-01',
          remark: '审核驳回：材料不齐全',
          createdAt: '2026-09-13T11:00:00',
        },
      ],
    } as never)
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('驳回理由：审核驳回：材料不齐全')
  })

  it('政务主体提交证书文件触发政务端点（B10 上传交互）', async () => {
    const { submitGovCertificate } = await import('../../api/subject')
    vi.mocked(submitGovCertificate).mockResolvedValue({
      conclusion: 'PASS',
      status: 'PENDING_REVIEW',
      failReason: null,
    })
    mockedProfile.mockResolvedValue(
      baseProfile({
        govCa: { uploaded: false, fileName: '', lastConclusion: null, lastFailReason: null, lastSubmittedAt: null },
      }) as never,
    )
    mockedDetail.mockResolvedValue(baseDetail('GOV') as never)
    const wrapper = await mountPage()
    const input = wrapper.find('input[type="file"]')
    const file = new File(['cert'], 'A3.cer')
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()
    expect(submitGovCertificate).toHaveBeenCalledWith('S20260913000002', file)
  })

  it('企业主体上传执照后出现核对确认区并收到 OCR 要素（回填链路锚定）', async () => {
    const { uploadLicense } = await import('../../api/subject')
    vi.mocked(uploadLicense).mockResolvedValue({
      fileName: 'A1.jpg',
      recognizable: true,
      ocrResult: { subjectName: '蓝天数据科技有限公司', uscc: '91330100MA27XW123X', legalPerson: '张伟', regAddress: '杭州市XX区' },
    })
    mockedProfile.mockResolvedValue(
      baseProfile({
        license: {
          uploaded: true,
          recognizable: true,
          confirmed: false,
          confirmedAt: null,
          confirmedResult: null,
        },
      }) as never,
    )
    mockedDetail.mockResolvedValue(baseDetail('ENTERPRISE') as never)
    const wrapper = await mountPage()
    const input = wrapper.find('input[type="file"]')
    const file = new File(['image'], 'A1.jpg')
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()
    expect(uploadLicense).toHaveBeenCalledWith('S20260913000002', file)
    expect(wrapper.text()).toContain('核对确认')
  })

  // ==== WBS-3.1.6 F2：交互值级断言 ====

  /** 按表单项 label 找输入框（核对确认与法人核验两个表单共用，标签文案为页面契约）。 */
  const inputByLabel = (wrapper: Awaited<ReturnType<typeof mountPage>>, label: string) => {
    const item = wrapper
      .findAll('.el-form-item')
      .filter((i) => {
        const l = i.find('.el-form-item__label')
        return l.exists() && l.text().replace('：', '') === label
      })[0]
    return item.find('input.el-input__inner')
  }

  it('OCR 四要素以值回填核对表单输入框（F2①：行为 2 第 2 条"回填供核对"的前端兑现——模板绑定改坏即红）', async () => {
    const { uploadLicense } = await import('../../api/subject')
    vi.mocked(uploadLicense).mockResolvedValue({
      fileName: 'A1.jpg',
      recognizable: true,
      ocrResult: { subjectName: '蓝天数据科技有限公司', uscc: '91330100MA27XW123X', legalPerson: '张伟', regAddress: '杭州市XX区' },
    })
    mockedProfile.mockResolvedValue(
      baseProfile({
        license: { uploaded: true, recognizable: true, confirmed: false, confirmedAt: null, confirmedResult: null },
      }) as never,
    )
    mockedDetail.mockResolvedValue(baseDetail('ENTERPRISE') as never)
    const wrapper = await mountPage()
    const fileInput = wrapper.find('input[type="file"]')
    Object.defineProperty(fileInput.element, 'files', { value: [new File(['image'], 'A1.jpg')] })
    await fileInput.trigger('change')
    await flushPromises()
    expect(inputByLabel(wrapper, '主体名称').element.value).toBe('蓝天数据科技有限公司')
    expect(inputByLabel(wrapper, '统一社会信用代码').element.value).toBe('91330100MA27XW123X')
    expect(inputByLabel(wrapper, '法定代表人').element.value).toBe('张伟')
    expect(inputByLabel(wrapper, '注册地址').element.value).toBe('杭州市XX区')
  })

  const confirmedEnterpriseProfile = () =>
    baseProfile({
      license: {
        uploaded: true,
        recognizable: true,
        confirmed: true,
        confirmedAt: '2026-09-13T10:00:00',
        confirmedResult: { subjectName: '蓝天数据科技有限公司', uscc: '91330100MA27XW123X', legalPerson: '张伟', regAddress: '杭州市XX区' },
      },
      remainingAttemptsToday: 4,
    })

  it('发起法人核验：要素透传端点、结论提示与当日剩余次数如实展示（F2②）', async () => {
    mockedVerify.mockResolvedValue({ conclusion: 'PASS' })
    mockedProfile.mockResolvedValue(confirmedEnterpriseProfile() as never)
    mockedDetail.mockResolvedValue(baseDetail('ENTERPRISE') as never)
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('当日剩余核验次数：4')
    await inputByLabel(wrapper, '法定代表人姓名').setValue('张伟')
    await inputByLabel(wrapper, '法定代表人身份证号').setValue('110101199001011229')
    const verifyButton = wrapper.findAll('button').filter((b) => b.text().includes('发起核验'))[0]
    await verifyButton.trigger('click')
    await flushPromises()
    expect(mockedVerify).toHaveBeenCalledWith('S20260913000002', {
      legalPersonName: '张伟',
      legalPersonIdNo: '110101199001011229',
    })
    expect(document.body.textContent).toContain('法人核验通过')
  })

  it('当日核验次数用完：后端业务文案如实展示（F2③，行为 3 验收-3 前端侧）', async () => {
    const { ApiError } = await import('../../api/client')
    mockedVerify.mockRejectedValue(new ApiError('1004B0005', '今日核验次数已用完，请次日再试'))
    mockedProfile.mockResolvedValue(confirmedEnterpriseProfile() as never)
    mockedDetail.mockResolvedValue(baseDetail('ENTERPRISE') as never)
    const wrapper = await mountPage()
    await inputByLabel(wrapper, '法定代表人姓名').setValue('张伟')
    await inputByLabel(wrapper, '法定代表人身份证号').setValue('110101199001011288')
    const verifyButton = wrapper.findAll('button').filter((b) => b.text().includes('发起核验'))[0]
    await verifyButton.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('今日核验次数已用完，请次日再试')
  })
})
