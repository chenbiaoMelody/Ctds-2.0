import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import ProductManageView from './ProductManageView.vue'
import {
  listMyProducts,
  listMyDatasets,
  createProduct,
  updateProduct,
  publishProduct,
  delistProduct,
  cancelProduct,
  listProductActionLogs,
  type ProviderProduct,
  type DatasetItem,
  type ProductActionLog,
} from '../../api/catalog'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import {
  SOURCE_DATASET_REQUIRED_TIP,
  PRICE_REQUIRED_TIP,
  MY_PRODUCTS_EMPTY_TIP,
  PRODUCT_LOGS_EMPTY_TIP,
  OPERATOR_MODE_TIP,
} from '../../constants/catalog'

/**
 * 产品上架页测试（WBS-3.3.6 hifi §6.5 + §6.8 弹窗 D/E/F/G + §7 T14/T15/T16/T20）：
 * - T14 列表：全状态渲染 + 来源资源名映射（R1 命中 / 超出页容量回退编号）+ 状态机按钮显隐 +
 *   运营档提示（1000C0005 原样 + 体验层切档提示）；
 * - T15 封装弹窗：来源资源下拉含本人全部资源（含已注销——服务端拒 1007C0016 为判定源）+
 *   免费档不提交数值 + 付费档数值前置校验 + 同名 1007C0017 原样 + 成功刷新；
 * - T16 上架/下架/重新上架/注销：定价不齐备 1007C0020 原样 + 下架后状态 + 重新上架 +
 *   在架注销 1007C0019 + 注销二次确认（取消零请求）；
 * - T20 产品留痕区：全值域渲染（DENIED_* / GOVERNANCE_VIEW / 强制下架理由全文）+ 空态 + 翻页。
 */
vi.mock('../../api/catalog', () => ({
  listMyProducts: vi.fn(),
  listMyDatasets: vi.fn(),
  createProduct: vi.fn(),
  updateProduct: vi.fn(),
  publishProduct: vi.fn(),
  delistProduct: vi.fn(),
  cancelProduct: vi.fn(),
  listProductActionLogs: vi.fn(),
}))

const mockedProducts = vi.mocked(listMyProducts)
const mockedDatasets = vi.mocked(listMyDatasets)
const mockedCreate = vi.mocked(createProduct)
const mockedPublish = vi.mocked(publishProduct)
const mockedDelist = vi.mocked(delistProduct)
const mockedCancel = vi.mocked(cancelProduct)
const mockedLogs = vi.mocked(listProductActionLogs)

function product(over: Partial<ProviderProduct> = {}): ProviderProduct {
  return {
    productId: 7,
    productName: '普惠金融数据服务（待定价版）',
    intro: '按次档但未填价格，供上架门槛判定',
    productType: '数据集',
    pricingModel: '按次',
    priceAmount: null,
    status: '未上架',
    providerSubjectNo: 'S20260925000001',
    datasetId: 9,
    categoryCode: 'finance',
    categoryName: '金融',
    listedAt: null,
    createdAt: '2026-10-02T21:34:52',
    ...over,
  }
}

function dataset(over: Partial<DatasetItem> = {}): DatasetItem {
  return {
    id: 9,
    dataNo: 'DS20261002000004',
    spaceId: 29,
    name: '小微企业信贷数据集',
    type: 'DATASET',
    intro: '简介',
    tags: [],
    declareCategory: '金融',
    declareLevel: 'L2',
    declareImportant: false,
    status: 'ACTIVE',
    createdAt: '2026-10-02T17:58:20',
    ...over,
  }
}

function log(over: Partial<ProductActionLog> = {}): ProductActionLog {
  return {
    id: 1,
    action: 'CREATE',
    operatorSubjectNo: 'S20260925000001',
    summary: '封装产品：普惠金融数据服务',
    createdAt: '2026-10-02T21:34:52',
    ...over,
  }
}

function page<T>(list: T[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/catalog/products', name: 'catalog-products', component: ProductManageView },
      { path: '/catalog/products/:productId', name: 'catalog-product-detail', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push('/catalog/products')
  await router.isReady()
  const wrapper = mount(ProductManageView, {
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
  for (const fn of [mockedProducts, mockedDatasets, mockedCreate, mockedPublish,
    mockedDelist, mockedCancel, mockedLogs]) {
    fn.mockReset()
  }
  setDemoSubject('S20260925000001')
  setActorMode('subject')
  mockedProducts.mockResolvedValue(page([product()]))
  mockedDatasets.mockResolvedValue({ list: [dataset(), dataset({ id: 6, name: '待注销数据集', status: 'DELETED' })], total: 2, pageNum: 1, pageSize: 100, totalPages: 1 })
})

describe('我的产品列表（T14）', () => {
  it('全状态渲染 + 状态机按钮显隐（DRAFT 上架/注销；LISTED 下架/注销；DELISTED 重新上架/注销；已注销无操作）', async () => {
    mockedProducts.mockResolvedValue(page([
      product({ productId: 4, status: '未上架' }),
      product({ productId: 3, productName: '已上架产品', status: '已上架', pricingModel: '包月', priceAmount: '19.90', listedAt: '2026-10-02T21:37:59' }),
      product({ productId: 1, productName: '已下架产品', status: '已下架' }),
      product({ productId: 6, productName: '已注销产品', status: '已注销' }),
    ]))
    const wrapper = await mountPage()
    expect(mockedProducts).toHaveBeenCalledWith(1, 10)
    expect(wrapper.text()).toContain('未上架')
    expect(wrapper.text()).toContain('已上架')
    expect(wrapper.text()).toContain('已下架')
    expect(wrapper.text()).toContain('已注销')
    // 状态机按钮显隐
    expect(wrapper.findAll('.publish-btn').length).toBe(1)
    expect(wrapper.findAll('.delist-btn').length).toBe(1)
    expect(wrapper.findAll('.republish-btn').length).toBe(1)
    // 已注销行（productId 6）无任何操作按钮：注销按钮只出现在未注销的三行
    expect(wrapper.findAll('.cancel-btn').length).toBe(3)
  })

  it('来源资源名映射：R1 命中显示资源名，超出页容量回退显示编号', async () => {
    mockedProducts.mockResolvedValue(page([
      product({ productId: 7, datasetId: 9 }),
      product({ productId: 8, productName: '跨页资源产品', datasetId: 999 }),
    ]))
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('小微企业信贷数据集')
    expect(wrapper.text()).toContain('999')
  })

  it('空列表展示统一空态文案（不是报错）', async () => {
    mockedProducts.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain(MY_PRODUCTS_EMPTY_TIP)
  })

  it('运营档打开本页：列表加载被拒（1000C0005）原样展示 + 体验层提示切回普通档', async () => {
    setActorMode('operator')
    mockedProducts.mockRejectedValue(new ApiError('1000C0005', '无权限执行该操作'))
    const wrapper = await mountPage()
    expect(document.body.textContent).toContain('无权限执行该操作')
    expect(wrapper.text()).toContain(OPERATOR_MODE_TIP)
    // 不得伪装成功/空列表之外的乐观状态
    expect(mockedDatasets).not.toHaveBeenCalled()
  })
})

describe('封装产品（T15）', () => {
  async function openDialog() {
    const wrapper = await mountPage()
    await wrapper.find('.create-open').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('来源资源下拉含本人全部资源（含已注销——封装由服务端拒 1007C0016，界面不拦截）', async () => {
    const wrapper = await openDialog()
    expect(wrapper.find('.create-dataset').exists()).toBe(true)
    // 展开下拉（选项 teleport 到 body）后核对全部本人资源
    await wrapper.find('.create-dataset').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('小微企业信贷数据集')
    expect(document.body.textContent).toContain('待注销数据集')
  })

  it('免费档：数值不提交；付费档：数值随档提交', async () => {
    mockedCreate.mockResolvedValue(product())
    const wrapper = await openDialog()
    await wrapper.findComponent('.create-dataset').setValue(9)
    await wrapper.find('.create-name input').setValue('普惠金融数据服务（免费体验版）')
    await wrapper.find('.create-intro textarea').setValue('免费体验档产品')
    await wrapper.findComponent('.create-type').setValue('DATASET')
    await wrapper.findComponent('.create-pricing').setValue('FREE')
    await wrapper.find('.create-submit').trigger('click')
    await flushPromises()
    expect(mockedCreate).toHaveBeenCalledWith({
      datasetId: 9,
      productName: '普惠金融数据服务（免费体验版）',
      intro: '免费体验档产品',
      productType: 'DATASET',
      pricingModel: 'FREE',
      categoryCode: undefined,
    })
    expect(mockedCreate.mock.calls[0][0]).not.toHaveProperty('priceAmount')

    await wrapper.findComponent('.create-pricing').setValue('PER_CALL')
    await wrapper.find('.create-price input').setValue('3.00')
    await wrapper.find('.create-submit').trigger('click')
    await flushPromises()
    expect(mockedCreate).toHaveBeenLastCalledWith(expect.objectContaining({ pricingModel: 'PER_CALL', priceAmount: '3.00' }))
  })

  it('付费档数值留空：前置拦截（零请求）', async () => {
    const wrapper = await openDialog()
    await wrapper.findComponent('.create-dataset').setValue(9)
    await wrapper.find('.create-name input').setValue('普惠金融数据服务')
    await wrapper.find('.create-intro textarea').setValue('简介')
    await wrapper.findComponent('.create-type').setValue('DATASET')
    await wrapper.findComponent('.create-pricing').setValue('PER_CALL')
    await wrapper.find('.create-submit').trigger('click')
    await flushPromises()
    expect(mockedCreate).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain(PRICE_REQUIRED_TIP)
  })

  it('来源资源留空：前置拦截（零请求）', async () => {
    const wrapper = await openDialog()
    await wrapper.find('.create-name input').setValue('普惠金融数据服务')
    await wrapper.find('.create-intro textarea').setValue('简介')
    await wrapper.findComponent('.create-type').setValue('DATASET')
    await wrapper.findComponent('.create-pricing').setValue('FREE')
    await wrapper.find('.create-submit').trigger('click')
    await flushPromises()
    expect(mockedCreate).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain(SOURCE_DATASET_REQUIRED_TIP)
  })

  it('同名产品 1007C0017 原样展示；成功后刷新列表', async () => {
    mockedCreate.mockRejectedValue(new ApiError('1007C0017', '同一提供方下已存在同名产品'))
    const wrapper = await openDialog()
    await wrapper.findComponent('.create-dataset').setValue(9)
    await wrapper.find('.create-name input').setValue('普惠金融数据服务')
    await wrapper.find('.create-intro textarea').setValue('简介')
    await wrapper.findComponent('.create-type').setValue('DATASET')
    await wrapper.findComponent('.create-pricing').setValue('FREE')
    await wrapper.find('.create-submit').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('同一提供方下已存在同名产品')

    mockedCreate.mockResolvedValue(product())
    await wrapper.find('.create-submit').trigger('click')
    await flushPromises()
    expect(mockedProducts).toHaveBeenCalledTimes(2)
  })
})

describe('上架 / 下架 / 重新上架 / 注销（T16）', () => {
  it('定价不齐备上架：1007C0020 原样展示且状态不变（不乐观更新）', async () => {
    mockedPublish.mockRejectedValue(new ApiError('1007C0020', '定价信息不齐备，无法上架'))
    const wrapper = await mountPage()
    await wrapper.find('.publish-btn').trigger('click')
    await flushPromises()
    expect(mockedPublish).toHaveBeenCalledWith(7)
    expect(document.body.textContent).toContain('定价信息不齐备，无法上架')
    expect(wrapper.text()).toContain('未上架')
  })

  it('下架 → 重新上架：均以响应为准刷新列表', async () => {
    mockedDelist.mockResolvedValue(product({ productId: 3, status: '已下架' }))
    mockedProducts.mockResolvedValueOnce(page([
      product({ productId: 3, productName: '已上架产品', status: '已上架' }),
    ]))
    // 下架成功后的刷新读到"已下架"页（以响应为准）
    mockedProducts.mockResolvedValue(page([product({ productId: 3, productName: '已上架产品', status: '已下架' })]))
    const wrapper = await mountPage()
    await wrapper.find('.delist-btn').trigger('click')
    await flushPromises()
    expect(mockedDelist).toHaveBeenCalledWith(3)
    expect(mockedProducts).toHaveBeenCalledTimes(2)

    mockedPublish.mockResolvedValue(product({ productId: 3, status: '已上架' }))
    mockedProducts.mockResolvedValue(page([product({ productId: 1, productName: '已下架产品', status: '已下架' })]))
    await wrapper.find('.republish-btn').trigger('click')
    await flushPromises()
    expect(mockedPublish).toHaveBeenCalledWith(3)
    expect(mockedProducts).toHaveBeenCalledTimes(3)
  })

  it('在架注销：服务端状态机拒绝 1007C0019 原样展示', async () => {
    mockedProducts.mockResolvedValue(page([product({ productId: 3, productName: '已上架产品', status: '已上架' })]))
    mockedCancel.mockRejectedValue(new ApiError('1007C0019', '产品当前状态不允许该操作'))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage()
    await wrapper.find('.cancel-btn').trigger('click')
    await flushPromises()
    expect(mockedCancel).toHaveBeenCalledWith(3)
    expect(document.body.textContent).toContain('产品当前状态不允许该操作')
  })

  it('注销二次确认：明示不可逆；取消 = 零请求；确认后提交并刷新', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue(new Error('cancel'))
    const wrapper = await mountPage()
    await wrapper.find('.cancel-btn').trigger('click')
    await flushPromises()
    expect(mockedCancel).not.toHaveBeenCalled()
    expect(String(confirmSpy.mock.calls[0][0])).toContain('不可恢复')

    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mockedCancel.mockResolvedValue(product({ status: '已注销' }))
    await wrapper.find('.cancel-btn').trigger('click')
    await flushPromises()
    expect(mockedCancel).toHaveBeenCalledWith(7)
    expect(mockedProducts).toHaveBeenCalledTimes(2)
  })
})

describe('变更产品（T16 / C-3.3 S2-3 承载：from→to 由服务端留痕）', () => {
  it('变更请求体只含可变白名单字段（名称不可变；仅改简介与定价）', async () => {
    mockedProducts.mockResolvedValue(page([product({ productId: 3, productName: '已上架产品', status: '已上架', pricingModel: '按次', priceAmount: '3.00' })]))
    vi.mocked(updateProduct).mockResolvedValue(product({ productId: 3, pricingModel: '包月', priceAmount: '19.90' }))
    const wrapper = await mountPage()
    await wrapper.find('.update-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.update-intro textarea').setValue('变更后的简介')
    await wrapper.findComponent('.update-pricing').setValue('MONTHLY')
    await wrapper.find('.update-price input').setValue('19.90')
    await wrapper.find('.update-submit').trigger('click')
    await flushPromises()
    expect(updateProduct).toHaveBeenCalledWith(3, {
      intro: '变更后的简介',
      pricingModel: 'MONTHLY',
      priceAmount: '19.90',
    })
    // 名称不可变：请求体不含 productName
    expect(vi.mocked(updateProduct).mock.calls[0][1]).not.toHaveProperty('productName')
    // 以响应为准刷新列表
    expect(mockedProducts).toHaveBeenCalledTimes(2)
  })

  it('切换免费档：数值清空且不提交 priceAmount', async () => {
    mockedProducts.mockResolvedValue(page([product({ productId: 3, productName: '已上架产品', status: '已上架', pricingModel: '按次', priceAmount: '3.00' })]))
    vi.mocked(updateProduct).mockResolvedValue(product({ productId: 3, pricingModel: '免费', priceAmount: null }))
    const wrapper = await mountPage()
    await wrapper.find('.update-btn').trigger('click')
    await flushPromises()
    await wrapper.findComponent('.update-pricing').setValue('FREE')
    await wrapper.find('.update-submit').trigger('click')
    await flushPromises()
    expect(vi.mocked(updateProduct).mock.calls[0][1]).not.toHaveProperty('priceAmount')
  })
})

describe('产品操作留痕区（T20）', () => {
  it('全值域渲染：封装/变更/上下架/强制下架（理由全文）/拒绝留痕/治理查看', async () => {
    mockedLogs.mockResolvedValue(page([
      log({ id: 2, action: 'UPDATE', summary: '定价：按次→包月' }),
      log({ id: 3, action: 'PUBLISH', summary: '上架：产品进入统一目录' }),
      log({ id: 4, action: 'FORCE_DELIST', summary: '强制下架：理由全文——目录信息与备案不符' }),
      log({ id: 5, action: 'DENIED_PUBLISH', summary: '上架被拒' }),
      log({ id: 6, action: 'GOVERNANCE_VIEW', summary: '治理查看：运营方 S20260925000003' }),
    ]))
    const wrapper = await mountPage()
    await wrapper.find('.log-btn').trigger('click')
    await flushPromises()
    expect(mockedLogs).toHaveBeenCalledWith(7, 1, 10)
    expect(wrapper.text()).toContain('定价：按次→包月')
    expect(wrapper.text()).toContain('目录信息与备案不符')
    expect(wrapper.text()).toContain('被拒')
    expect(wrapper.text()).toContain('治理查看')
  })

  it('空态与翻页：空列表展示统一空态；重开留痕区后翻页按新页码重新拉取', async () => {
    mockedLogs.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 })
    const wrapper = await mountPage()
    await wrapper.find('.log-btn').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(PRODUCT_LOGS_EMPTY_TIP)

    // 重新打开留痕区（total=25 分页可用）后翻页
    mockedLogs.mockResolvedValue({ list: [log({ id: 9 })], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    await wrapper.find('.log-btn').trigger('click')
    await flushPromises()
    mockedLogs.mockResolvedValue({ list: [log({ id: 10 })], total: 25, pageNum: 2, pageSize: 10, totalPages: 3 })
    await wrapper.find('.product-logs-pager .btn-next').trigger('click')
    await flushPromises()
    expect(mockedLogs).toHaveBeenLastCalledWith(7, 2, 10)
  })
})
