import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import GovernanceView from './GovernanceView.vue'
import {
  getProductGovernance,
  getDatasetGovernance,
  forceDelistProduct,
  type ProviderProduct,
  type DatasetItem,
} from '../../api/catalog'
import { ApiError, setDemoSubject } from '../../api/client'
import { FORCE_REASON_REQUIRED_TIP, QUERY_ID_REQUIRED_TIP } from '../../constants/catalog'

/**
 * 目录治理页测试（WBS-3.3.6 hifi §6.6 + §6.8 弹窗 F；承载剧本 C-3.2 S3-3 / C-3.3 S3-3 判定面）：
 * - 按编号直查：产品治理信息（任意状态全量字段）+ 资源治理信息（含申报字段与已注销对象）；
 * - 强制下架：理由必填（留空 = 前置拦截零请求）+ 提交后以响应为准刷新；
 * - 留痕说明：每次查看写 GOVERNANCE_VIEW（提供方可在留痕区核对）——说明文案经常量承载。
 */
vi.mock('../../api/catalog', () => ({
  getProductGovernance: vi.fn(),
  getDatasetGovernance: vi.fn(),
  forceDelistProduct: vi.fn(),
}))

const mockedProductGov = vi.mocked(getProductGovernance)
const mockedDatasetGov = vi.mocked(getDatasetGovernance)
const mockedForceDelist = vi.mocked(forceDelistProduct)

function governedProduct(over: Partial<ProviderProduct> = {}): ProviderProduct {
  return {
    productId: 3,
    productName: '普惠金融数据服务',
    intro: '面向普惠金融场景',
    productType: '数据集',
    pricingModel: '包月',
    priceAmount: '19.90',
    status: '已上架',
    providerSubjectNo: 'S20260925000001',
    datasetId: 9,
    categoryCode: 'finance',
    categoryName: '金融',
    listedAt: '2026-10-02T21:37:59',
    createdAt: '2026-10-02T21:34:52',
    ...over,
  }
}

function governedDataset(over: Partial<DatasetItem> = {}): DatasetItem {
  return {
    id: 9,
    dataNo: 'DS20261002000004',
    spaceId: 29,
    name: '小微企业信贷数据集',
    type: 'DATASET',
    intro: '简介',
    tags: ['金融', '风控'],
    declareCategory: '金融',
    declareLevel: 'L2',
    declareImportant: false,
    status: 'ACTIVE',
    createdAt: '2026-10-02T17:58:20',
    ...over,
  }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/catalog/governance', name: 'catalog-governance', component: GovernanceView },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push('/catalog/governance')
  await router.isReady()
  const wrapper = mount(GovernanceView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  for (const fn of [mockedProductGov, mockedDatasetGov, mockedForceDelist]) {
    fn.mockReset()
  }
  setDemoSubject('S20260925000003')
})

describe('治理直查（C-3.2 S3-3 / C-3.3 S3-3 承载）', () => {
  it('编号留空：前置拦截（零请求）', async () => {
    const wrapper = await mountPage()
    await wrapper.find('.query-product-btn').trigger('click')
    await flushPromises()
    expect(mockedProductGov).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain(QUERY_ID_REQUIRED_TIP)

    await wrapper.find('.query-dataset-btn').trigger('click')
    await flushPromises()
    expect(mockedDatasetGov).not.toHaveBeenCalled()
  })

  it('产品编号直查：全量治理字段渲染（任意状态）；在架产品给出强制下架入口', async () => {
    mockedProductGov.mockResolvedValue(governedProduct())
    const wrapper = await mountPage()
    await wrapper.find('.product-id-input input').setValue('3')
    await wrapper.find('.query-product-btn').trigger('click')
    await flushPromises()
    expect(mockedProductGov).toHaveBeenCalledWith(3)
    const text = wrapper.text()
    expect(text).toContain('普惠金融数据服务')
    expect(text).toContain('已上架')
    expect(text).toContain('包月')
    expect(text).toContain('19.90')
    expect(text).toContain('S20260925000001')
    expect(text).toContain('9')
    expect(wrapper.find('.force-delist-btn').exists()).toBe(true)
  })

  it('资源编号直查：全量字段渲染（含申报字段与已注销对象）', async () => {
    mockedDatasetGov.mockResolvedValue(governedDataset({ id: 6, name: '待注销数据集', status: 'DELETED' }))
    const wrapper = await mountPage()
    await wrapper.find('.dataset-id-input input').setValue('6')
    await wrapper.find('.query-dataset-btn').trigger('click')
    await flushPromises()
    expect(mockedDatasetGov).toHaveBeenCalledWith(6)
    const text = wrapper.text()
    expect(text).toContain('待注销数据集')
    expect(text).toContain('已注销')
    expect(text).toContain('金融')
    expect(text).toContain('L2')
  })

  it('直查对象缺失（治理例外显式语义，404 语义业务码）：后端文案原样展示', async () => {
    mockedProductGov.mockRejectedValue(new ApiError('1007C0012', '产品不存在或无权操作'))
    const wrapper = await mountPage()
    await wrapper.find('.product-id-input input').setValue('999')
    await wrapper.find('.query-product-btn').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('产品不存在或无权操作')
  })
})

describe('强制下架（C-3.3 S3-3 判定面）', () => {
  it('理由留空：前置拦截（零请求）', async () => {
    mockedProductGov.mockResolvedValue(governedProduct())
    const wrapper = await mountPage()
    await wrapper.find('.product-id-input input').setValue('3')
    await wrapper.find('.query-product-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.force-delist-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.force-delist-submit').trigger('click')
    await flushPromises()
    expect(mockedForceDelist).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain(FORCE_REASON_REQUIRED_TIP)
  })

  it('填写理由：按 {forceReason} 提交并以响应为准重新直查（留痕含理由与操作者由服务端承载）', async () => {
    mockedProductGov.mockResolvedValue(governedProduct())
    mockedForceDelist.mockResolvedValue(governedProduct({ status: '已下架', listedAt: null }))
    const wrapper = await mountPage()
    await wrapper.find('.product-id-input input').setValue('3')
    await wrapper.find('.query-product-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.force-delist-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.force-reason textarea').setValue('目录信息与备案不符，强制下架')
    await wrapper.find('.force-delist-submit').trigger('click')
    await flushPromises()
    expect(mockedForceDelist).toHaveBeenCalledWith(3, '目录信息与备案不符，强制下架')
    // 以响应为准刷新治理视图（不做乐观更新：直查一次重新发生）
    expect(mockedProductGov).toHaveBeenCalledTimes(2)
  })

  it('非在架产品强制下架：1007C0019 原样展示', async () => {
    mockedProductGov.mockResolvedValue(governedProduct({ status: '已下架' }))
    mockedForceDelist.mockRejectedValue(new ApiError('1007C0019', '产品当前状态不允许该操作'))
    const wrapper = await mountPage()
    await wrapper.find('.product-id-input input').setValue('3')
    await wrapper.find('.query-product-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.force-delist-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.force-reason textarea').setValue('理由')
    await wrapper.find('.force-delist-submit').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('产品当前状态不允许该操作')
  })
})
