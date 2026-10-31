import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import PortalProductDetailView from './PortalProductDetailView.vue'
import {
  getProductDetail,
  favoriteProduct,
  unfavoriteProduct,
  subscribeProduct,
  unsubscribeProduct,
  listFavorites,
  listSubscriptions,
  type CatalogProductDetail,
  type FavoriteItem,
  type SubscriptionItem,
  type FavoriteView,
} from '../../api/catalog'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import {
  PRODUCT_NOT_ACCESSIBLE_TIP,
  PORTAL_ORDER_PLACEHOLDER_TIP,
} from '../../constants/catalog'

/**
 * 门户详情测试（WBS-3.7.2 hifi §3.2 / §7 T6~T7）：
 * - 元数据 + 定价四档展示（免费显式"免费"；付费档带数值）；
 * - 收藏 / 订阅动作条仅普通档；写后重拉状态（无乐观更新）；失败文案原样；
 * - 订购入口为禁用占位（零请求）；不可达 1007C0011 → 同形 alert。
 */
vi.mock('../../api/catalog', () => ({
  getProductDetail: vi.fn(),
  favoriteProduct: vi.fn(),
  unfavoriteProduct: vi.fn(),
  subscribeProduct: vi.fn(),
  unsubscribeProduct: vi.fn(),
  listFavorites: vi.fn(),
  listSubscriptions: vi.fn(),
}))

const mockedDetail = vi.mocked(getProductDetail)
const mockedFavorite = vi.mocked(favoriteProduct)
const mockedUnfavorite = vi.mocked(unfavoriteProduct)
const mockedSubscribe = vi.mocked(subscribeProduct)
const mockedUnsubscribe = vi.mocked(unsubscribeProduct)
const mockedFavorites = vi.mocked(listFavorites)
const mockedSubscriptions = vi.mocked(listSubscriptions)

function detail(over: Partial<CatalogProductDetail> = {}): CatalogProductDetail {
  return {
    productId: 15,
    productName: 'W353 Demo API Feed',
    intro: '面向普惠金融场景的目录元数据产品（全文）',
    productType: 'API接口',
    pricingModel: '按次',
    priceAmount: '3.00',
    categoryCode: 'finance',
    categoryName: '金融',
    providerSubjectNo: 'S20260925000001',
    listedAt: '2026-10-20T22:02:01',
    createdAt: '2026-10-20T21:59:00',
    status: '已上架',
    ...over,
  }
}

function favoriteItem(over: Partial<FavoriteItem> = {}): FavoriteItem {
  return {
    productId: 15, productName: 'W353 Demo API Feed', intro: '简介', productType: 'API接口',
    pricingModel: '按次', categoryCode: 'finance', categoryName: '金融',
    providerSubjectNo: 'S20260925000001', listedAt: null, productStatus: '已上架',
    favoritedAt: '2026-10-31T21:27:27',
    ...over,
  }
}

function subscriptionItem(over: Partial<SubscriptionItem> = {}): SubscriptionItem {
  return {
    productId: 15, productName: 'W353 Demo API Feed', intro: '简介', productType: 'API接口',
    pricingModel: '按次', categoryCode: 'finance', categoryName: '金融',
    providerSubjectNo: 'S20260925000001', listedAt: null, productStatus: '已上架',
    subscribedAt: '2026-10-31T21:27:27',
    ...over,
  }
}

function page<T>(list: T[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 100, totalPages: 1 }
}

async function mountPage(path = '/portal/products/15') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/portal', name: 'portal-home', component: { template: '<div />' } },
      { path: '/portal/products/:productId', name: 'portal-product-detail', component: PortalProductDetailView },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(PortalProductDetailView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  for (const fn of [mockedDetail, mockedFavorite, mockedUnfavorite, mockedSubscribe,
    mockedUnsubscribe, mockedFavorites, mockedSubscriptions]) {
    fn.mockReset()
  }
  setDemoSubject('S20260925000001')
  setActorMode('subject')
  mockedDetail.mockResolvedValue(detail())
  mockedFavorites.mockResolvedValue(page([]))
  mockedSubscriptions.mockResolvedValue(page([]))
})

describe('元数据与定价（T6 / B2-4）', () => {
  it('逐字段渲染 + 返回链接指向门户首页 + 按次单价可见', async () => {
    const { wrapper } = await mountPage()
    const text = wrapper.text()
    expect(text).toContain('W353 Demo API Feed')
    expect(text).toContain('已上架')
    expect(text).toContain('面向普惠金融场景的目录元数据产品（全文）')
    expect(text).toContain('API接口')
    expect(text).toContain('按次（3.00）')
    expect(wrapper.find('.pricing-value').text()).toBe('按次（3.00）')
    expect(text).toContain('金融')
    expect(text).toContain('S20260925000001')
    expect(text).toContain('2026-10-20T22:02:01')
    expect(wrapper.find('.back-link').attributes('href')).toBe('/portal')
  })

  it('免费档显式展示"免费"（不得空缺）', async () => {
    mockedDetail.mockResolvedValue(detail({ pricingModel: '免费', priceAmount: null }))
    const { wrapper } = await mountPage()
    expect(wrapper.find('.pricing-value').text()).toBe('免费')
    expect(wrapper.find('.pricing-tag .el-tag').classes()).toContain('el-tag--success')
  })

  it('交易额分成档展示登记数值：交易额分成（5.00）', async () => {
    mockedDetail.mockResolvedValue(detail({ pricingModel: '交易额分成', priceAmount: '5.00' }))
    const { wrapper } = await mountPage()
    expect(wrapper.find('.pricing-value').text()).toBe('交易额分成（5.00）')
  })

  it('付费档无数值（理论不发生）：仅展示模型名，不臆造括号空值', async () => {
    mockedDetail.mockResolvedValue(detail({ pricingModel: '包月', priceAmount: null }))
    const { wrapper } = await mountPage()
    expect(wrapper.find('.pricing-value').text()).toBe('包月')
    expect(wrapper.text()).not.toContain('包月（）')
  })

  it('不可达分支（1007C0011）：同形提示常量展示，不渲染任何产品数据', async () => {
    mockedDetail.mockRejectedValue(new ApiError('1007C0011', PRODUCT_NOT_ACCESSIBLE_TIP))
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain(PRODUCT_NOT_ACCESSIBLE_TIP)
    expect(wrapper.find('.product-name').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('W353 Demo API Feed')
  })

  it('其他错误：服务端文案原样 toast，不渲染半页数据', async () => {
    mockedDetail.mockRejectedValue(new ApiError('1007S0001', '主体服务不可用，请稍后重试'))
    const { wrapper } = await mountPage()
    expect(document.body.textContent).toContain('主体服务不可用，请稍后重试')
    expect(wrapper.find('.product-name').exists()).toBe(false)
  })

  it('非法深链 id：不发起详情 / 收藏 / 订阅请求，展示同形不可达提示', async () => {
    const { wrapper } = await mountPage('/portal/products/abc')
    expect(mockedDetail).not.toHaveBeenCalled()
    expect(mockedFavorites).not.toHaveBeenCalled()
    expect(mockedSubscriptions).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(PRODUCT_NOT_ACCESSIBLE_TIP)
    expect(wrapper.find('.product-name').exists()).toBe(false)
  })
})

describe('订购禁用占位（T6 / Q3-A）', () => {
  it('「发起订购」为禁用态并常驻提示；点击零请求、零状态变化', async () => {
    const { wrapper, router } = await mountPage()
    const orderBtn = wrapper.find('.order-btn')
    expect(orderBtn.exists()).toBe(true)
    expect(orderBtn.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain(PORTAL_ORDER_PLACEHOLDER_TIP)

    await orderBtn.trigger('click')
    await flushPromises()
    expect(mockedFavorite).not.toHaveBeenCalled()
    expect(mockedSubscribe).not.toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('portal-product-detail')
  })
})

describe('收藏 / 订阅动作（T7）', () => {
  it('普通档进入即用 R9 / R10 初查本人状态（一次性 100 条）', async () => {
    await mountPage()
    expect(mockedFavorites).toHaveBeenCalledWith({ pageNum: 1, pageSize: 100 })
    expect(mockedSubscriptions).toHaveBeenCalledWith({ pageNum: 1, pageSize: 100 })
  })

  it('已收藏 / 已订阅：动作条按当前状态翻转', async () => {
    mockedFavorites.mockResolvedValue(page([favoriteItem()]))
    mockedSubscriptions.mockResolvedValue(page([subscriptionItem()]))
    const { wrapper } = await mountPage()
    expect(wrapper.find('.favorite-btn').exists()).toBe(false)
    expect(wrapper.find('.unfavorite-btn').exists()).toBe(true)
    expect(wrapper.find('.subscribe-btn').exists()).toBe(false)
    expect(wrapper.find('.unsubscribe-btn').exists()).toBe(true)
  })

  it('收藏成功：调用 W4 + 成功提示 + 重拉 R9（以响应为准，不做乐观更新）', async () => {
    mockedFavorite.mockResolvedValue({ productId: 15, favoritedAt: '2026-10-31T21:27:27' })
    mockedFavorites.mockResolvedValueOnce(page([])).mockResolvedValueOnce(page([favoriteItem()]))
    const { wrapper } = await mountPage()
    await wrapper.find('.favorite-btn').trigger('click')
    await flushPromises()
    expect(mockedFavorite).toHaveBeenCalledWith(15)
    expect(mockedFavorites).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('已收藏')
    expect(wrapper.find('.unfavorite-btn').exists()).toBe(true)
  })

  it('取消收藏成功：调用 W5 + 重拉 R9', async () => {
    mockedFavorites.mockResolvedValueOnce(page([favoriteItem()])).mockResolvedValueOnce(page([]))
    mockedUnfavorite.mockResolvedValue({ productId: 15, favoritedAt: '2026-10-31T21:27:27' })
    const { wrapper } = await mountPage()
    await wrapper.find('.unfavorite-btn').trigger('click')
    await flushPromises()
    expect(mockedUnfavorite).toHaveBeenCalledWith(15)
    expect(mockedFavorites).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.favorite-btn').exists()).toBe(true)
  })

  it('订阅成功：调用 W6 + 重拉 R10', async () => {
    mockedSubscribe.mockResolvedValue({ productId: 15, subscribedAt: '2026-10-31T21:27:27' })
    mockedSubscriptions.mockResolvedValueOnce(page([])).mockResolvedValueOnce(page([subscriptionItem()]))
    const { wrapper } = await mountPage()
    await wrapper.find('.subscribe-btn').trigger('click')
    await flushPromises()
    expect(mockedSubscribe).toHaveBeenCalledWith(15)
    expect(mockedSubscriptions).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.unsubscribe-btn').exists()).toBe(true)
  })

  it('退订成功：调用 W7 + 重拉 R10', async () => {
    mockedSubscriptions.mockResolvedValueOnce(page([subscriptionItem()])).mockResolvedValueOnce(page([]))
    mockedUnsubscribe.mockResolvedValue({ productId: 15, subscribedAt: '2026-10-31T21:27:27' })
    const { wrapper } = await mountPage()
    await wrapper.find('.unsubscribe-btn').trigger('click')
    await flushPromises()
    expect(mockedUnsubscribe).toHaveBeenCalledWith(15)
    expect(mockedSubscriptions).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.subscribe-btn').exists()).toBe(true)
  })

  it('写失败：服务端文案原样展示，界面状态不变（不乐观更新）', async () => {
    mockedFavorite.mockRejectedValue(new ApiError('1007C0011', PRODUCT_NOT_ACCESSIBLE_TIP))
    const { wrapper } = await mountPage()
    await wrapper.find('.favorite-btn').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(PRODUCT_NOT_ACCESSIBLE_TIP)
    expect(wrapper.find('.favorite-btn').exists()).toBe(true)
    expect(wrapper.find('.unfavorite-btn').exists()).toBe(false)
  })

  it('写请求进行中：按钮 loading 防误触', async () => {
    let resolveFavorite: (value: FavoriteView) => void = () => undefined
    mockedFavorite.mockReturnValue(new Promise<FavoriteView>((resolve) => { resolveFavorite = resolve }))
    const { wrapper } = await mountPage()
    await wrapper.find('.favorite-btn').trigger('click')
    await flushPromises()
    expect(wrapper.find('.favorite-btn').classes()).toContain('is-loading')
    resolveFavorite({ productId: 15, favoritedAt: '2026-10-31T21:27:27' })
    await flushPromises()
  })

  it('运营档：不渲染动作条与订购区，且不调用 R9 / R10（体验层；服务端仍为判定源）', async () => {
    setActorMode('operator')
    const { wrapper } = await mountPage()
    expect(wrapper.find('.action-bar').exists()).toBe(false)
    expect(wrapper.find('.order-area').exists()).toBe(false)
    expect(wrapper.find('.order-btn').exists()).toBe(false)
    expect(mockedFavorites).not.toHaveBeenCalled()
    expect(mockedSubscriptions).not.toHaveBeenCalled()
  })
})
