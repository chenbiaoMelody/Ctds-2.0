import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import DemoView from './DemoView.vue'
import { demoSign, fetchVerificationLogs, resolveDid, verifySignature } from '../../api/did'
import { ApiError } from '../../api/client'

/**
 * WBS-3.1.11 DID 演示与验证页测试（hifi T14/T15；规格 §6 第 6 条演示入口边界）：
 * 代签 → 验证链路（结果逐字展示、系统态与业务态样式分离）、解析"未登记"业务答复样式、
 * 演示入口未启用（1000C0003）与"操作失败"分开呈现、验证留痕列表与"无原文"说明。
 */
vi.mock('../../api/did', () => ({
  fetchDidRecords: vi.fn(),
  fetchOperationLogs: vi.fn(),
  fetchVerificationLogs: vi.fn(),
  resolveDid: vi.fn(),
  demoSign: vi.fn(),
  verifySignature: vi.fn(),
  revokeDid: vi.fn(),
  reissueDid: vi.fn(),
  retryIssuance: vi.fn(),
}))

const mockedSign = vi.mocked(demoSign)
const mockedVerify = vi.mocked(verifySignature)
const mockedResolve = vi.mocked(resolveDid)
const mockedLogs = vi.mocked(fetchVerificationLogs)

const DID = 'did:ctds:S20260925000001.1'

const mountPage = async (query = '') => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/did', name: 'did-management', component: { template: '<div />' } },
      { path: '/did/demo', name: 'did-demo', component: { template: '<div />' } },
    ],
  })
  await router.push(`/did/demo${query}`)
  await router.isReady()
  const wrapper = mount(DemoView, { global: { plugins: [ElementPlus, router] } })
  await flushPromises()
  return { wrapper, router }
}

/** 读取 el-alert 组件的 type 属性（样式分离断言：warning=系统态/环境态，error=业务态不通过）。 */
const alertType = (wrapper: ReturnType<typeof mount>, selector: string): unknown => {
  const alert = wrapper.findComponent(selector) as unknown as { props: (name: string) => unknown }
  return alert.props('type')
}

describe('DID 演示与验证页（WBS-3.1.11 T14/T15）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mockedLogs.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 } as never)
  })

  it('演示代签成功：展示原文与签名 Base64，并自动填入验证区', async () => {
    mockedSign.mockResolvedValue({
      did: DID,
      data: '5Lid5rWL5paH5pys',
      signature: 'MEYCIQ==',
      signedAt: '2026-09-25T12:00:00',
    })
    const { wrapper } = await mountPage(`?did=${encodeURIComponent(DID)}`)
    // 从列表带入的 DID 预填
    expect((wrapper.find('.demo-did-input input').element as HTMLInputElement).value).toBe(DID)

    await wrapper.find('.demo-text-input textarea').setValue('蓝天数据科技有限公司确认接入城市可信数据空间')
    await wrapper.find('.demo-sign-btn').trigger('click')
    await flushPromises()

    expect(mockedSign).toHaveBeenCalledWith(DID, '蓝天数据科技有限公司确认接入城市可信数据空间')
    expect((wrapper.find('.demo-data-result input').element as HTMLInputElement).value).toBe('5Lid5rWL5paH5pys')
    expect((wrapper.find('.demo-signature-result input').element as HTMLInputElement).value).toBe('MEYCIQ==')
    // 自动填入验证区（承载剧本 S3 步骤 5"用步骤 1 留存的签名复核"）
    expect((wrapper.find('.verify-data input').element as HTMLInputElement).value).toBe('5Lid5rWL5paH5pys')
    expect((wrapper.find('.verify-signature input').element as HTMLInputElement).value).toBe('MEYCIQ==')
  })

  it('演示入口未启用（1000C0003）：以提示样式说明"仅演示/调试期"，不呈现为操作失败', async () => {
    mockedSign.mockRejectedValue(new ApiError('1000C0003', '演示签名入口未启用（仅演示/调试期）'))
    const { wrapper } = await mountPage(`?did=${encodeURIComponent(DID)}`)
    await wrapper.find('.demo-text-input textarea').setValue('确认文本')
    await wrapper.find('.demo-sign-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.demo-alert').exists()).toBe(true)
    expect(wrapper.find('.demo-alert').text()).toContain('演示签名入口未启用（仅演示/调试期）')
    expect(alertType(wrapper, '.demo-alert')).toBe('warning')
    expect(wrapper.text()).not.toContain('操作失败')
  })

  it('验证不通过：error 样式 + 失败原因逐字展示（签名核验失败）', async () => {
    mockedVerify.mockResolvedValue({
      did: DID,
      result: 'FAIL',
      reason: 'SIGNATURE_INVALID',
      verifiedAt: '2026-09-25T12:10:00',
    })
    const { wrapper } = await mountPage(`?did=${encodeURIComponent(DID)}`)
    await wrapper.find('.verify-data input').setValue('5Lid5rWL5paH5pys')
    await wrapper.find('.verify-signature input').setValue('MEYCIQ==')
    await wrapper.find('.verify-btn').trigger('click')
    await flushPromises()

    expect(mockedVerify).toHaveBeenCalledWith(DID, '5Lid5rWL5paH5pys', 'MEYCIQ==')
    expect(wrapper.find('.verify-result').text()).toContain('不通过')
    expect(wrapper.find('.verify-result').text()).toContain('签名核验失败')
    expect(alertType(wrapper, '.verify-alert')).toBe('error')
  })

  it('验证通道不可用（系统态）：warning 样式展示"不可用（系统态）"，不冒充"不通过"', async () => {
    mockedVerify.mockResolvedValue({
      did: DID,
      result: 'UNAVAILABLE',
      reason: 'BINDING_UNAVAILABLE',
      verifiedAt: '2026-09-25T12:11:00',
    })
    const { wrapper } = await mountPage(`?did=${encodeURIComponent(DID)}`)
    await wrapper.find('.verify-data input').setValue('5Lid5rWL5paH5pys')
    await wrapper.find('.verify-signature input').setValue('MEYCIQ==')
    await wrapper.find('.verify-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.verify-result').text()).toContain('不可用（系统态）')
    expect(wrapper.find('.verify-result').text()).toContain('绑定服务不可用')
    expect(alertType(wrapper, '.verify-alert')).toBe('warning')
    expect(wrapper.find('.verify-result').text()).not.toContain('不通过')
  })

  it('解析未登记 DID：以业务答复（warning）呈现"未登记该 DID"，不用报错样式', async () => {
    mockedResolve.mockRejectedValue(new ApiError('1005B0003', '该 DID 未登记'))
    const { wrapper } = await mountPage()
    await wrapper.find('.resolve-did input').setValue('did:ctds:S20260925999999.1')
    await wrapper.find('.resolve-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.resolve-alert').text()).toContain('未登记该 DID')
    expect(alertType(wrapper, '.resolve-alert')).toBe('warning')
  })

  it('解析成功：展示状态与公开要素（公钥/控制者/创建时间），并提示不展示私钥', async () => {
    mockedResolve.mockResolvedValue({
      did: DID,
      status: 'ACTIVE',
      document: {
        did: DID,
        publicKey: { type: 'SM2', algorithm: 'sm2p256v1', valueHex: '04abcdef' },
        controller: 'S20260925000001',
        service: [{ id: '#resolution', type: 'DidResolution', serviceEndpoint: '/api/v1/did' }],
        created: '2026-09-25T10:00:00',
      },
    })
    const { wrapper } = await mountPage()
    await wrapper.find('.resolve-did input').setValue(DID)
    await wrapper.find('.resolve-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.resolve-result').text()).toContain('有效')
    expect(wrapper.find('.resolve-result').text()).toContain('04abcdef')
    expect(wrapper.find('.resolve-result').text()).toContain('S20260925000001')
    expect(wrapper.text()).toContain('仅公开要素，界面不展示任何私钥')
  })

  it('验证留痕列表：时间/DID/结果/原因逐列展示，且固定说明不留存业务原文', async () => {
    mockedLogs.mockResolvedValue({
      list: [
        {
          did: DID,
          result: 'FAIL',
          reason: 'REVOKED',
          occurredAt: '2026-09-25T12:20:00',
        },
      ],
      total: 1,
      pageNum: 1,
      pageSize: 10,
      totalPages: 1,
    } as never)
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('留痕仅含时间/DID/结果/原因，不保存任何业务数据原文')
    expect(wrapper.text()).toContain('状态已吊销')
    expect(wrapper.text()).toContain('2026-09-25T12:20:00')
  })

  it('验证留痕空态：展示"暂无验证留痕"，并仍明示不含数据原文（F2）', async () => {
    // beforeEach 已把留痕置空（total=0）——空态必须给出明确文案，不能留白
    const { wrapper } = await mountPage()

    expect(wrapper.find('.el-table__empty-block').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂无验证留痕')
    expect(wrapper.text()).toContain('留痕仅含时间/DID/结果/原因，不保存任何业务数据原文')
  })

  it('页面顶部固定提示：入口仅演示/调试期、生产默认关闭', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain('仅演示/调试期使用')
    expect(wrapper.text()).toContain('生产环境默认关闭')
  })
})
