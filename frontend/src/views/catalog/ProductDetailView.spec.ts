import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import ProductDetailView from './ProductDetailView.vue'
import {
  getProductDetail,
  getProductChangeLogs,
  favoriteProduct,
  unfavoriteProduct,
  subscribeProduct,
  unsubscribeProduct,
  listFavorites,
  listSubscriptions,
  type CatalogProductDetail,
  type ProductChangeLog,
  type FavoriteItem,
  type SubscriptionItem,
} from '../../api/catalog'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import { PRODUCT_NOT_ACCESSIBLE_TIP, CHANGE_LOGS_SUBSCRIPTION_REQUIRED_TIP } from '../../constants/catalog'

/**
 * 产品详情页测试（WBS-3.3.6 hifi §6.3 + §7 T6/T7/T10）：
 * - T6 元数据逐字段渲染；不可达分支（非在架/不存在）共用同一条提示常量（反向探针：改文案必红，
 *   由源集守卫承载）；数据本体零接触由源集守卫 T6 源集面承载；
 * - T7 收藏/订阅：成功 + 重复（幂等重放：提示成功且列表不重复）/ 非在架 1007C0011 原样 /
 *   运营档动作条不显示（体验层）；
 * - T10 变更记录区：已订阅加载 R8 / 未订阅显示提示零请求 / R8 同形拒绝原样展示。
 */
vi.mock('../../api/catalog', () => ({
  getProductDetail: vi.fn(),
  getProductChangeLogs: vi.fn(),
  favoriteProduct: vi.fn(),
  unfavoriteProduct: vi.fn(),
  subscribeProduct: vi.fn(),
  unsubscribeProduct: vi.fn(),
  listFavorites: vi.fn(),
  listSubscriptions: vi.fn(),
}))

const mockedDetail = vi.mocked(getProductDetail)
const mockedChangeLogs = vi.mocked(getProductChangeLogs)
const mockedFavorite = vi.mocked(favoriteProduct)
const mockedUnfavorite = vi.mocked(unfavoriteProduct)
const mockedSubscribe = vi.mocked(subscribeProduct)
const mockedUnsubscribe = vi.mocked(unsubscribeProduct)
const mockedFavorites = vi.mocked(listFavorites)
const mockedSubscriptions = vi.mocked(listSubscriptions)

function detail(over: Partial<CatalogProductDetail> = {}): CatalogProductDetail {
  return {
    productId: 7,
    productName: '普惠金融数据服务',
    intro: '面向普惠金融场景的目录元数据产品（全文）',
    productType: '数据集',
    pricingModel: '按次',
    categoryCode: 'finance',
    categoryName: '金融',
    providerSubjectNo: 'S20260925000001',
    listedAt: '2026-10-02T21:37:59',
    createdAt: '2026-10-02T21:34:52',
    status: '已上架',
    ...over,
  }
}

function changeLog(over: Partial<ProductChangeLog> = {}): ProductChangeLog {
  return {
    action: 'PUBLISH',
    summary: '上架：产品进入统一目录',
    operatorSubjectNo: 'S20260925000001',
    createdAt: '2026-10-02T21:37:59',
    ...over,
  }
}

function favoriteItem(over: Partial<FavoriteItem> = {}): FavoriteItem {
  return {
    productId: 7, productName: '普惠金融数据服务', intro: '简介', productType: '数据集',
    pricingModel: '按次', categoryCode: 'finance', categoryName: '金融',
    providerSubjectNo: 'S20260925000001', listedAt: null, productStatus: '已上架',
    favoritedAt: '2026-10-02T22:00:00',
    ...over,
  }
}

function subscriptionItem(over: Partial<SubscriptionItem> = {}): SubscriptionItem {
  return {
    productId: 7, productName: '普惠金融数据服务', intro: '简介', productType: '数据集',
    pricingModel: '按次', categoryCode: 'finance', categoryName: '金融',
    providerSubjectNo: 'S20260925000001', listedAt: null, productStatus: '已上架',
    subscribedAt: '2026-10-02T22:01:00',
    ...over,
  }
}

function page<T>(list: T[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 100, totalPages: 1 }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
      { path: '/catalog/products/:productId', name: 'catalog-product-detail', component: ProductDetailView },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push('/catalog/products/7')
  await router.isReady()
  const wrapper = mount(ProductDetailView, {
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
  for (const fn of [mockedDetail, mockedChangeLogs, mockedFavorite, mockedUnfavorite,
    mockedSubscribe, mockedUnsubscribe, mockedFavorites, mockedSubscriptions]) {
    fn.mockReset()
  }
  setDemoSubject('S20260925000001')
  setActorMode('subject')
  mockedDetail.mockResolvedValue(detail())
  mockedFavorites.mockResolvedValue(page([]))
  mockedSubscriptions.mockResolvedValue(page([]))
})

describe('详情元数据（T6）', () => {
  it('逐字段渲染：名称 / 状态 / 简介全文 / 形态 / 定价模型 / 类目 / 提供方主体编号 / 上架时间；且无"价格数值"行（F2 修复批：R7 出参无该字段）', async () => {
    const wrapper = await mountPage()
    const text = wrapper.text()
    expect(text).toContain('普惠金融数据服务')
    expect(text).toContain('已上架')
    expect(text).toContain('面向普惠金融场景的目录元数据产品（全文）')
    expect(text).toContain('数据集')
    expect(text).toContain('按次')
    // hifi §6.3 字段清单无"价格数值"；R7 出参（3.3.4 既有契约）亦无该字段 → 不得展示该行
    expect(text).not.toContain('价格数值')
    expect(text).toContain('金融')
    expect(text).toContain('S20260925000001')
    expect(text).toContain('2026-10-02T21:37:59')
  })

  it('不可达分支（1007C0011）：同一条提示常量展示，不区分"不存在/未上架/已下架/已注销"', async () => {
    for (const scenario of ['产品已下架', '产品不存在']) {
      // 后端对两类场景返回同码同文案（基线实测）；界面展示同一条常量
      mockedDetail.mockRejectedValue(new ApiError('1007C0011', PRODUCT_NOT_ACCESSIBLE_TIP))
      document.body.innerHTML = ''
      const wrapper = await mountPage()
      expect(wrapper.text()).toContain(PRODUCT_NOT_ACCESSIBLE_TIP)
      expect(wrapper.text()).not.toContain(scenario === '产品已下架' ? '产品已下架，不可查看' : '产品不存在，不可查看')
      wrapper.unmount()
    }
  })
})

describe('收藏 / 订阅动作（T7）', () => {
  it('收藏成功后可取消收藏（提示成功且列表不重复——幂等重放由服务端 uk 兜底）', async () => {
    mockedFavorite.mockResolvedValue({ productId: 7, favoritedAt: '2026-10-02T22:00:00' })
    const wrapper = await mountPage()
    expect(wrapper.find('.favorite-btn').exists()).toBe(true)
    expect(wrapper.find('.unfavorite-btn').exists()).toBe(false)

    // 收藏成功后的刷新将读到已收藏状态（以响应为准，不做乐观更新；收藏列表恒 1 条 = 不重复）
    mockedFavorites.mockResolvedValue(page([favoriteItem()]))
    await wrapper.find('.favorite-btn').trigger('click')
    await flushPromises()
    expect(mockedFavorite).toHaveBeenCalledWith(7)
    expect(mockedFavorites).toHaveBeenCalledTimes(2)
    // 幂等重放判定面：提示成功 + 状态翻转后重复收藏入口消失（不新增条目）
    expect(document.body.textContent).toContain('已收藏')
    expect(wrapper.text()).toContain('普惠金融数据服务')
    expect(wrapper.findAll('.favorite-btn').length).toBe(0)
    expect(wrapper.find('.unfavorite-btn').exists()).toBe(true)
  })

  it('订阅成功：状态翻转并加载变更记录区；订阅被拒 1007C0011 原样且状态不变', async () => {
    mockedSubscribe.mockResolvedValue({ productId: 7, subscribedAt: '2026-10-02T22:01:00' })
    const wrapper = await mountPage()
    expect(wrapper.find('.subscribe-btn').exists()).toBe(true)
    expect(wrapper.find('.change-logs-tip').exists()).toBe(true)

    // 订阅成功后的刷新读到已订阅状态 → 动作条翻转 + 变更记录区加载（T10 联动）
    mockedSubscriptions.mockResolvedValue(page([subscriptionItem()]))
    mockedChangeLogs.mockResolvedValue({ list: [changeLog()], total: 1, pageNum: 1, pageSize: 10, totalPages: 1 })
    await wrapper.find('.subscribe-btn').trigger('click')
    await flushPromises()
    expect(mockedSubscribe).toHaveBeenCalledWith(7)
    expect(mockedSubscriptions).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.unsubscribe-btn').exists()).toBe(true)
    expect(mockedChangeLogs).toHaveBeenCalledWith(7, 1, 10)

    // 订阅被拒（1007C0011 原样）且状态不变（不乐观更新）
    document.body.innerHTML = ''
    mockedSubscriptions.mockResolvedValue(page([]))
    mockedChangeLogs.mockReset()
    mockedSubscribe.mockRejectedValue(new ApiError('1007C0011', PRODUCT_NOT_ACCESSIBLE_TIP))
    const wrapper2 = await mountPage()
    await wrapper2.find('.subscribe-btn').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(PRODUCT_NOT_ACCESSIBLE_TIP)
    expect(wrapper2.find('.subscribe-btn').exists()).toBe(true)
    expect(wrapper2.find('.unsubscribe-btn').exists()).toBe(false)
  })

  it('已收藏状态：动作条按当前状态翻转（重复收藏不再产生新收藏动作——幂等由服务端承载）', async () => {
    mockedFavorites.mockResolvedValue(page([favoriteItem()]))
    const wrapper = await mountPage()
    await flushPromises()
    expect(wrapper.find('.favorite-btn').exists()).toBe(false)
    expect(wrapper.find('.unfavorite-btn').exists()).toBe(true)

    const favoriteCalls = mockedFavorite.mock.calls.length
    mockedUnfavorite.mockResolvedValue({ productId: 7, favoritedAt: null })
    mockedFavorites.mockResolvedValue(page([]))
    await wrapper.find('.unfavorite-btn').trigger('click')
    await flushPromises()
    expect(mockedUnfavorite).toHaveBeenCalledWith(7)
    expect(mockedFavorite.mock.calls.length).toBe(favoriteCalls)
  })

  it('对非在架产品新发起收藏：1007C0011 原样展示且状态不变（不乐观更新）', async () => {
    mockedFavorites.mockResolvedValue(page([]))
    mockedFavorite.mockRejectedValue(new ApiError('1007C0011', PRODUCT_NOT_ACCESSIBLE_TIP))
    const wrapper = await mountPage()
    await wrapper.find('.favorite-btn').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(PRODUCT_NOT_ACCESSIBLE_TIP)
    // 被拒后界面状态不变：收藏按钮仍在（未翻转）
    expect(wrapper.find('.favorite-btn').exists()).toBe(true)
  })

  it('运营档：动作条不显示（体验层；服务端仍为判定源）', async () => {
    setActorMode('operator')
    const wrapper = await mountPage()
    expect(wrapper.find('.action-bar').exists()).toBe(false)
  })
})

describe('变更记录区（T10）', () => {
  it('已订阅：加载 R8 并渲染动作 / 摘要 / 操作者 / 时间', async () => {
    mockedSubscriptions.mockResolvedValue(page([subscriptionItem()]))
    mockedChangeLogs.mockResolvedValue({ list: [changeLog()], total: 1, pageNum: 1, pageSize: 10, totalPages: 1 })
    const wrapper = await mountPage()
    expect(mockedChangeLogs).toHaveBeenCalledWith(7, 1, 10)
    expect(wrapper.text()).toContain('上架：产品进入统一目录')
    expect(wrapper.text()).toContain('S20260925000001')
  })

  it('未订阅：显示订阅引导提示且零请求（不调 R8）', async () => {
    const wrapper = await mountPage()
    expect(mockedChangeLogs).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(CHANGE_LOGS_SUBSCRIPTION_REQUIRED_TIP)
  })

  it('R8 同形拒绝（1007C0011）：原样展示后端文案', async () => {
    mockedSubscriptions.mockResolvedValue(page([subscriptionItem()]))
    mockedChangeLogs.mockRejectedValue(new ApiError('1007C0011', PRODUCT_NOT_ACCESSIBLE_TIP))
    await mountPage()
    expect(document.body.textContent).toContain(PRODUCT_NOT_ACCESSIBLE_TIP)
  })
})
