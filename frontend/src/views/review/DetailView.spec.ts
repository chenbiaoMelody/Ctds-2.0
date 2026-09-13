import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DetailView from './DetailView.vue'
import { fetchProfile, fetchSubjectDetail, fetchLicenseImage, approveSubject, rejectSubject } from '../../api/subject'

/**
 * WBS-3.1.5 审核详情页测试：档案分区渲染（政务段/核验段互斥——规格行为 6"全程不出现"的界面兑现）、
 * 影像放大调用、驳回空理由拦截、通过调用。
 */
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ params: { subjectNo: 'S20260913000001' } }),
}))

vi.mock('../../api/subject', () => ({
  fetchProfile: vi.fn(),
  fetchSubjectDetail: vi.fn(),
  fetchLicenseImage: vi.fn(),
  approveSubject: vi.fn(),
  rejectSubject: vi.fn(),
}))

const mockedProfile = vi.mocked(fetchProfile)
const mockedDetail = vi.mocked(fetchSubjectDetail)
const mockedImage = vi.mocked(fetchLicenseImage)
const mockedApprove = vi.mocked(approveSubject)
const mockedReject = vi.mocked(rejectSubject)

const pendingEnterpriseProfile = {
  subjectNo: 'S20260913000001',
  status: 'PENDING_REVIEW',
  license: {
    uploaded: true,
    recognizable: true,
    confirmed: true,
    confirmedAt: '2026-09-13T10:00:00',
    confirmedResult: { subjectName: '蓝天数据科技有限公司', uscc: '91330100MA27XW123X', legalPerson: '张伟', regAddress: '杭州市XX区' },
  },
  govCa: null,
  verifications: [{ conclusion: 'PASS', failReason: null, createdAt: '2026-09-13T09:58:00' }],
  remainingAttemptsToday: 4,
}

const pendingGovProfile = {
  subjectNo: 'S20260913000002',
  status: 'PENDING_REVIEW',
  license: null,
  govCa: {
    uploaded: true,
    fileName: 'A3.cer',
    lastConclusion: 'PASS',
    lastFailReason: null,
    lastSubmittedAt: '2026-09-13T10:05:00',
  },
  verifications: [],
  remainingAttemptsToday: null,
}

const subjectDetail = {
  subject: {
    subjectNo: 'S20260913000001',
    subjectName: '蓝天数据科技有限公司',
    uscc: '91330100MA27XW123X',
    subjectType: 'ENTERPRISE',
    regAddress: '杭州市XX区',
    contactName: '张三',
    contactPhone: '13800001234',
    adminAccount: 'admin001',
    status: 'PENDING_REVIEW',
  },
  transitions: [
    {
      fromStatus: 'PENDING_CERT',
      toStatus: 'PENDING_REVIEW',
      triggerRole: 'SYSTEM',
      operator: 'SYSTEM',
      remark: '政务 CA 证书验证通过，自动流转',
      createdAt: '2026-09-13T10:00:00',
    },
  ],
}

const mountPage = async () => {
  mockedDetail.mockResolvedValue(subjectDetail as never)
  const wrapper = mount(DetailView, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  return wrapper
}

describe('审核工作台详情页（WBS-3.1.5）', () => {
  it('企业主体档案：显示核验记录与审核操作，不显示政务证书段', async () => {
    mockedProfile.mockResolvedValue(pendingEnterpriseProfile as never)
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('核验记录')
    expect(wrapper.text()).toContain('当日剩余核验次数')
    expect(wrapper.text()).toContain('审核操作')
    // 负向断言用政务卡独有字段（证书文件名），避免与留痕备注文案撞车
    expect(wrapper.text()).not.toContain('证书文件名')
  })

  it('政务主体档案：显示政务证书验证段，不显示核验记录段（行为 6 界面兑现）', async () => {
    mockedProfile.mockResolvedValue(pendingGovProfile as never)
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('政务 CA 证书验证')
    expect(wrapper.text()).toContain('A3.cer')
    expect(wrapper.text()).not.toContain('核验记录')
  })

  it('影像放大调用影像查看端点并展示弹层', async () => {
    mockedProfile.mockResolvedValue(pendingEnterpriseProfile as never)
    mockedImage.mockResolvedValue({ fileName: 'A1.jpg', dataUrl: 'data:image/jpeg;base64,xyz' })
    const wrapper = await mountPage()
    await wrapper.find('.el-card button').trigger('click')
    await flushPromises()
    expect(mockedImage).toHaveBeenCalledWith('S20260913000001')
  })

  it('驳回理由为空时拦截提交，不调用驳回端点（理由必填）', async () => {
    mockedProfile.mockResolvedValue(pendingEnterpriseProfile as never)
    const wrapper = await mountPage()
    const buttons = wrapper.findAll('button').filter((b) => b.text().includes('驳回'))
    await buttons[0].trigger('click')
    await flushPromises()
    const confirm = wrapper.findAll('button').filter((b) => b.text().includes('确认驳回'))
    await confirm[0].trigger('click')
    await flushPromises()
    expect(mockedReject).not.toHaveBeenCalled()
  })

  it('通过按钮触发审核通过端点（ElMessageBox 确认后）', async () => {
    mockedProfile.mockResolvedValue(pendingEnterpriseProfile as never)
    mockedApprove.mockResolvedValue({ subjectNo: 'S20260913000001', status: 'ADMITTED' })
    const elementPlus = await import('element-plus')
    vi.spyOn(elementPlus.ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage()
    const buttons = wrapper.findAll('button').filter((b) => b.text().includes('通过（转已入驻）'))
    await buttons[0].trigger('click')
    await flushPromises()
    expect(mockedApprove).toHaveBeenCalledWith('S20260913000001')
  })

  it('注册信息分区与流转留痕渲染（行为 5 第 1 条 + 剧本 S1 步骤 9 可见性）', async () => {
    mockedProfile.mockResolvedValue(pendingEnterpriseProfile as never)
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('注册信息')
    expect(wrapper.text()).toContain('蓝天数据科技有限公司')
    expect(wrapper.text()).toContain('91330100MA27XW123X')
    expect(wrapper.text()).toContain('流转留痕')
    expect(wrapper.text()).toContain('政务 CA 证书验证通过，自动流转')
  })

  it('填写驳回理由后确认驳回，调用驳回端点（正向路径）', async () => {
    mockedProfile.mockResolvedValue(pendingEnterpriseProfile as never)
    mockedReject.mockResolvedValue({ subjectNo: 'S20260913000001', status: 'REJECTED' })
    const wrapper = await mountPage()
    const buttons = wrapper.findAll('button').filter((b) => b.text().includes('驳回'))
    await buttons[0].trigger('click')
    await flushPromises()
    const textarea = wrapper.find('.el-dialog textarea')
    await textarea.setValue('证照材料不齐全')
    const confirm = wrapper.findAll('button').filter((b) => b.text().includes('确认驳回'))
    await confirm[0].trigger('click')
    await flushPromises()
    expect(mockedReject).toHaveBeenCalledWith('S20260913000001', '证照材料不齐全')
  })
})
