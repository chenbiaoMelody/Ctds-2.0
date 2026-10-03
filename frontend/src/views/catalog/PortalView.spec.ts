import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import PortalView from './PortalView.vue'
import {
  listCategories,
  searchProducts,
  listFavorites,
  unfavoriteProduct,
  listSubscriptions,
  listInteractionLogs,
  type CategoryNode,
  type CatalogProduct,
  type FavoriteItem,
  type SubscriptionItem,
  type InteractionLog,
} from '../../api/catalog'
import { ApiError } from '../../api/client'
import { setDemoSubject } from '../../api/client'
import {
  SEARCH_EMPTY_TIP,
  FAVORITES_EMPTY_TIP,
  SUBSCRIPTIONS_EMPTY_TIP,
  INTERACTION_LOGS_EMPTY_TIP,
  CATALOG_ADMISSION_REQUIRED_MESSAGE,
} from '../../constants/catalog'

/**
 * 检索门户测试（WBS-3.3.6 hifi §6.2 + §7 T4/T5/T8/T9）：
 * - T4 门户检索：类目树渲染（2 级）/ 选中类目传 categoryCode（父类目含子树由服务端展开）/
 *   关键词 ≤64 传参 / 重置 / 空态 / 分页传参刷新 / 越界 1000C0001 原样展示且表格保持原数据；
 * - T5 门户未入驻：1007C0006 统一文案原样展示（不出现"主体不存在"差异表述）；
 * - T8 我的收藏/订阅：productStatus 三态标记（条目保留）/ 空态 / 翻页 / 取消收藏刷新；
 * - T9 互动留痕页签：四动作与拒绝行渲染 / 空态。
 */
vi.mock('../../api/catalog', () => ({
  listCategories: vi.fn(),
  searchProducts: vi.fn(),
  listFavorites: vi.fn(),
  favoriteProduct: vi.fn(),
  unfavoriteProduct: vi.fn(),
  listSubscriptions: vi.fn(),
  subscribeProduct: vi.fn(),
  unsubscribeProduct: vi.fn(),
  listInteractionLogs: vi.fn(),
}))

const mockedCategories = vi.mocked(listCategories)
const mockedSearch = vi.mocked(searchProducts)
const mockedFavorites = vi.mocked(listFavorites)
const mockedUnfavorite = vi.mocked(unfavoriteProduct)
const mockedSubscriptions = vi.mocked(listSubscriptions)
const mockedInteractions = vi.mocked(listInteractionLogs)

let currentRouter: Router

function category(over: Partial<CategoryNode> = {}): CategoryNode {
  return {
    categoryCode: 'finance',
    categoryName: '金融',
    children: [
      { categoryCode: 'finance-banking', categoryName: '银行保险', children: [] },
      { categoryCode: 'finance-inclusive', categoryName: '普惠金融', children: [] },
    ],
    ...over,
  }
}

function product(over: Partial<CatalogProduct> = {}): CatalogProduct {
  return {
    productId: 7,
    productName: '普惠金融数据服务',
    intro: '面向普惠金融场景的目录元数据产品',
    productType: '数据集',
    pricingModel: '按次',
    priceAmount: '3.00',
    categoryCode: 'finance',
    categoryName: '金融',
    providerSubjectNo: 'S20260925000001',
    listedAt: '2026-10-02T21:37:59',
    ...over,
  }
}

function favorite(over: Partial<FavoriteItem> = {}): FavoriteItem {
  return {
    productId: 7,
    productName: '普惠金融数据服务',
    intro: '简介',
    productType: '数据集',
    pricingModel: '按次',
    priceAmount: '3.00',
    categoryCode: 'finance',
    categoryName: '金融',
    providerSubjectNo: 'S20260925000001',
    listedAt: '2026-10-02T21:37:59',
    productStatus: '已上架',
    favoritedAt: '2026-10-02T22:00:00',
    ...over,
  }
}

function subscription(over: Partial<SubscriptionItem> = {}): SubscriptionItem {
  return {
    productId: 7,
    productName: '普惠金融数据服务',
    intro: '简介',
    productType: '数据集',
    pricingModel: '按次',
    priceAmount: '3.00',
    categoryCode: 'finance',
    categoryName: '金融',
    providerSubjectNo: 'S20260925000001',
    listedAt: '2026-10-02T21:37:59',
    productStatus: '已下架',
    subscribedAt: '2026-10-02T22:01:00',
    ...over,
  }
}

function interaction(over: Partial<InteractionLog> = {}): InteractionLog {
  return {
    id: 11,
    productId: 7,
    action: 'FAVORITE',
    outcome: 'SUCCEEDED',
    denyReason: null,
    createdAt: '2026-10-02T22:00:00',
    ...over,
  }
}

function page<T>(list: T[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage(route = '/catalog') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/catalog', name: 'catalog', component: PortalView },
      { path: '/catalog/products/:productId', name: 'catalog-product-detail', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  currentRouter = router
  await router.push(route)
  await router.isReady()
  const wrapper = mount(PortalView, {
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
  for (const fn of [mockedCategories, mockedSearch, mockedFavorites, mockedUnfavorite,
    mockedSubscriptions, mockedInteractions]) {
    fn.mockReset()
  }
  setDemoSubject('S20260925000001')
  mockedCategories.mockResolvedValue([category()])
  mockedSearch.mockResolvedValue(page([product()]))
})

describe('门户检索（T4）', () => {
  it('类目树渲染两级结构；选中类目按 categoryCode 传参检索（父类目含子树由服务端展开）', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('金融')
    expect(wrapper.text()).toContain('银行保险')
    expect(wrapper.text()).toContain('普惠金融')

    await wrapper.findAll('.el-tree-node__content')[0].trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith(1, 10, '', 'finance')
  })

  it('关键词查询按 ≤64 传参；重置清空关键词与类目过滤后重新拉取', async () => {
    const wrapper = await mountPage()
    await wrapper.find('.keyword-input input').setValue('普惠')
    await wrapper.find('.search-btn').trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith(1, 10, '普惠', '')

    await wrapper.findAll('.el-tree-node__content')[0].trigger('click')
    await flushPromises()
    await wrapper.find('.reset-btn').trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith(1, 10, '', '')
  })

  it('空结果展示统一空态文案（不是报错）', async () => {
    mockedSearch.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain(SEARCH_EMPTY_TIP)
  })

  it('翻页按新页码重新拉取；越界 1000C0001 原样展示且表格保持原数据（不乐观更新）', async () => {
    mockedSearch.mockResolvedValue({ list: [product()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()

    mockedSearch.mockResolvedValue({ list: [product({ productId: 8, productName: '小微企业信贷数据服务' })], total: 25, pageNum: 2, pageSize: 10, totalPages: 3 })
    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith(2, 10, '', '')
    expect(wrapper.text()).toContain('小微企业信贷数据服务')

    mockedSearch.mockRejectedValue(new ApiError('1000C0001', '分页参数超出范围'))
    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('分页参数超出范围')
    expect(wrapper.text()).toContain('小微企业信贷数据服务')
  })

  it('结果行"查看详情"进入产品详情深链路由', async () => {
    const wrapper = await mountPage()
    await wrapper.find('.detail-btn').trigger('click')
    await flushPromises()
    expect(currentRouter.currentRoute.value.path).toBe('/catalog/products/7')
  })
})

describe('门户未入驻（T5）', () => {
  it('1007C0006 统一业务文案原样展示（不出现"主体不存在"差异表述）', async () => {
    mockedSearch.mockRejectedValue(new ApiError('1007C0006', CATALOG_ADMISSION_REQUIRED_MESSAGE))
    await mountPage()
    expect(document.body.textContent).toContain(CATALOG_ADMISSION_REQUIRED_MESSAGE)
    // 防枚举：界面不得自行区分"该主体不存在"类差异表述
    expect(document.body.textContent).not.toContain('该主体不存在')
  })
})

describe('我的收藏 / 我的订阅（T8）', () => {
  it('收藏页签：条目渲染产品当前状态三态标记（历史不删除）+ 取消收藏入口', async () => {
    mockedFavorites.mockResolvedValue(page([
      favorite(),
      favorite({ productId: 5, productName: '普惠金融数据服务（已下架对照）', productStatus: '已下架' }),
      favorite({ productId: 6, productName: '普惠金融数据服务（已注销对照）', productStatus: '已注销' }),
    ]))
    const wrapper = await mountPage()
    await wrapper.find('#tab-favorites').trigger('click')
    await flushPromises()
    expect(mockedFavorites).toHaveBeenCalledWith(1, 10)
    expect(wrapper.text()).toContain('已上架')
    expect(wrapper.text()).toContain('已下架')
    expect(wrapper.text()).toContain('已注销')
  })

  it('收藏页签空态：空列表展示统一空态文案（不是报错）', async () => {
    mockedFavorites.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    await wrapper.find('#tab-favorites').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(FAVORITES_EMPTY_TIP)
  })

  it('订阅页签：翻页按新页码重新拉取（条目与状态渲染同收藏页签）', async () => {
    mockedSubscriptions.mockResolvedValue({ list: [subscription()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPage()
    await wrapper.find('#tab-subscriptions').trigger('click')
    await flushPromises()
    expect(mockedSubscriptions).toHaveBeenCalledWith(1, 10)

    mockedSubscriptions.mockResolvedValue({ list: [subscription({ productId: 8, productName: '小微企业信贷数据服务' })], total: 25, pageNum: 2, pageSize: 10, totalPages: 3 })
    await wrapper.find('.subscriptions-pager .btn-next').trigger('click')
    await flushPromises()
    expect(mockedSubscriptions).toHaveBeenLastCalledWith(2, 10)
    expect(wrapper.text()).toContain('小微企业信贷数据服务')
  })

  it('订阅页签空态：空列表展示统一空态文案（不是报错）', async () => {
    mockedSubscriptions.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    await wrapper.find('#tab-subscriptions').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(SUBSCRIPTIONS_EMPTY_TIP)
  })

  it('取消收藏：调用端点后刷新收藏列表（以响应为准，不做乐观更新）', async () => {
    mockedFavorites.mockResolvedValue(page([favorite()]))
    mockedUnfavorite.mockResolvedValue({ productId: 7, favoritedAt: null } as never)
    const wrapper = await mountPage()
    await wrapper.find('#tab-favorites').trigger('click')
    await flushPromises()
    await wrapper.find('.unfavorite-btn').trigger('click')
    await flushPromises()
    expect(mockedUnfavorite).toHaveBeenCalledWith(7)
    expect(mockedFavorites).toHaveBeenCalledTimes(2)
  })
})

describe('我的互动留痕（T9）', () => {
  it('四动作与拒绝行渲染：动作 / 结果 / 产品编号 / 时间 / 拒绝原因（错误码尾号）', async () => {
    mockedInteractions.mockResolvedValue(page([
      interaction(),
      interaction({ id: 12, action: 'SUBSCRIBE', outcome: 'SUCCEEDED', denyReason: null }),
      interaction({ id: 13, action: 'UNFAVORITE', outcome: 'DENIED', denyReason: 'C0012', createdAt: '2026-10-02T22:05:00' }),
    ]))
    const wrapper = await mountPage()
    await wrapper.find('#tab-interactions').trigger('click')
    await flushPromises()
    expect(mockedInteractions).toHaveBeenCalledWith(1, 10)
    expect(wrapper.text()).toContain('收藏')
    expect(wrapper.text()).toContain('订阅')
    expect(wrapper.text()).toContain('被拒')
    expect(wrapper.text()).toContain('C0012')
  })

  it('空列表展示统一空态文案（不是报错）', async () => {
    mockedInteractions.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    await wrapper.find('#tab-interactions').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(INTERACTION_LOGS_EMPTY_TIP)
  })
})
