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
  fetchEvaluationStats,
  fetchProductEvaluations,
  fetchMyEvaluation,
  submitEvaluation,
  hideEvaluation,
  type EvaluationItem,
} from '../../api/review'
import {
  REVIEW_ELIGIBILITY_DENIED_TIP,
  REVIEW_SCORE_INVALID_TIP,
  REVIEW_HIDE_CONFIRM_TEXT,
  REVIEW_EMPTY_TIP,
} from '../../constants/review'
import {
  PRODUCT_NOT_ACCESSIBLE_TIP,
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
vi.mock('../../api/contract', () => ({
  listTemplates: vi.fn(),
  getTemplate: vi.fn(),
}))
vi.mock('../../api/review', () => ({
  fetchEvaluationStats: vi.fn(),
  fetchProductEvaluations: vi.fn(),
  fetchMyEvaluation: vi.fn(),
  submitEvaluation: vi.fn(),
  hideEvaluation: vi.fn(),
}))
// ElMessageBox.confirm 单点 mock（隐藏确认弹窗）；其余 ElementPlus 导出（含 ElMessage toast）保持真实实现
vi.mock('element-plus', async (importOriginal) => {
  const original = await importOriginal<typeof import('element-plus')>()
  return { ...original, ElMessageBox: { confirm: vi.fn(async () => 'confirm') } }
})

const mockedDetail = vi.mocked(getProductDetail)
const mockedFavorite = vi.mocked(favoriteProduct)
const mockedUnfavorite = vi.mocked(unfavoriteProduct)
const mockedSubscribe = vi.mocked(subscribeProduct)
const mockedUnsubscribe = vi.mocked(unsubscribeProduct)
const mockedFavorites = vi.mocked(listFavorites)
const mockedSubscriptions = vi.mocked(listSubscriptions)
const mockedStats = vi.mocked(fetchEvaluationStats)
const mockedList = vi.mocked(fetchProductEvaluations)
const mockedMine = vi.mocked(fetchMyEvaluation)
const mockedSubmit = vi.mocked(submitEvaluation)
const mockedHide = vi.mocked(hideEvaluation)

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

function reviewItem(over: Partial<EvaluationItem> = {}): EvaluationItem {
  return {
    reviewId: 1,
    requesterNo: 'S20260925000008',
    score: 4,
    comment: null,
    createdAt: '2026-11-01T10:00:00',
    hidden: false,
    ...over,
  }
}

async function mountPage(path = '/portal/products/15') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/portal', name: 'portal-home', component: { template: '<div />' } },
      { path: '/portal/products/:productId', name: 'portal-product-detail', component: PortalProductDetailView },
      { path: '/portal/orders/:orderNo', name: 'portal-order-detail', component: { template: '<div />' } },
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
  // 评价域默认态：两人已评（4.3 分）+ 本人未评（WBS-3.7.4）
  mockedStats.mockResolvedValue({ productId: 15, averageScore: 4.3, reviewCount: 2 })
  mockedList.mockResolvedValue({
    productId: 15,
    items: [
      reviewItem({ reviewId: 2, requesterNo: 'S20260925000009', score: 5, comment: '五星好评' }),
      reviewItem({ reviewId: 1, requesterNo: 'S20260925000008', score: 4, comment: null }),
    ],
  })
  mockedMine.mockResolvedValue(null)
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

  it.each(['abc', '0', '-1'])(
    '非法深链 id（%s）：不发起详情 / 收藏 / 订阅请求，展示同形不可达提示',
    async (rawId) => {
      const { wrapper } = await mountPage(`/portal/products/${rawId}`)
      expect(mockedDetail).not.toHaveBeenCalled()
      expect(mockedFavorites).not.toHaveBeenCalled()
      expect(mockedSubscriptions).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain(PRODUCT_NOT_ACCESSIBLE_TIP)
      expect(wrapper.find('.product-name').exists()).toBe(false)
    },
  )
})

describe('订购入口（WBS-3.7.3 开放 / hifi §4.1 仅改订购区）', () => {
  it('「发起订购」启用；点击挂载订购弹窗；关闭弹窗零跳转、收藏订阅零误触', async () => {
    const { wrapper, router } = await mountPage()
    const orderBtn = wrapper.find('.order-btn')
    expect(orderBtn.exists()).toBe(true)
    expect(orderBtn.attributes('disabled')).toBeUndefined()

    await orderBtn.trigger('click')
    await flushPromises()
    const dialog = wrapper.findComponent({ name: 'PortalOrderDialog' })
    expect(dialog.exists()).toBe(true)
    expect(router.currentRoute.value.name).toBe('portal-product-detail')
    expect(mockedFavorite).not.toHaveBeenCalled()
    expect(mockedSubscribe).not.toHaveBeenCalled()

    dialog.vm.$emit('close')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'PortalOrderDialog' }).exists()).toBe(false)
  })

  it('订购成功：关闭弹窗并路由跳转订单详情深链', async () => {
    const { wrapper, router } = await mountPage()
    await wrapper.find('.order-btn').trigger('click')
    await flushPromises()
    wrapper.findComponent({ name: 'PortalOrderDialog' }).vm.$emit('ordered', 'OD000001')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/portal/orders/OD000001')
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

describe('产品评价区（WBS-3.7.4 / 规格 B5；hifi §5.2）', () => {
  it('统计条与列表渲染：均分 1 位小数 + 评价数；条目只读星级 + 文字 + 评价人·时点', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.find('.review-section').exists()).toBe(true)
    expect(wrapper.find('.review-stats').text()).toBe('平均评分 4.3 · 共 2 条评价')
    const items = wrapper.findAll('.review-item')
    expect(items).toHaveLength(2)
    expect(items[0].text()).toContain('五星好评')
    expect(items[0].text()).toContain('评价人 S20260925000009 · 2026-11-01 10:00')
    expect(items[1].text()).not.toContain('undefined')
    // 只读星级（展示不可改；Element Plus disabled 态类名 is-disabled）
    expect(items[0].find('.el-rate').classes()).toContain('is-disabled')
    // 本人未评价：无「修改」按钮
    expect(items[0].find('.review-edit-btn').exists()).toBe(false)
  })

  it('空态：无评价 = 暂无评价（统计 null + 零条目）', async () => {
    mockedStats.mockResolvedValue({ productId: 15, averageScore: null, reviewCount: 0 })
    mockedList.mockResolvedValue({ productId: 15, items: [] })
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain(REVIEW_EMPTY_TIP)
    expect(wrapper.find('.review-item').exists()).toBe(false)
  })

  it('提交表单：可选文字 + 提交按钮；本人已评价时预填回显且按钮转「更新评价」', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.find('.review-form').exists()).toBe(true)
    expect(wrapper.find('.review-submit-btn').text()).toBe('提交评价')
    mockedMine.mockResolvedValue(
      reviewItem({ reviewId: 1, requesterNo: 'S20260925000001', score: 4, comment: '初评' }))
    const { wrapper: again } = await mountPage()
    expect(again.find('.review-submit-btn').text()).toBe('更新评价')
    const textarea = again.find('.review-form textarea')
    expect((textarea.element as HTMLTextAreaElement).value).toBe('初评')
  })

  it('提交成功：调用 W-EV1（带 requestId）并重拉统计/列表（主动刷新，无轮询）', async () => {
    mockedSubmit.mockResolvedValue({
      reviewId: 9, productId: 15, score: 4, comment: '不错',
      createdAt: '2026-11-01T12:00:00', updated: false,
    })
    const { wrapper } = await mountPage()
    // 交互目标 = 表单内的 rate（列表只读星级同为 .el-rate__item，须限定表单域）
    await wrapper.find('.review-form .el-rate').findAll('.el-rate__item')[3].trigger('click')
    await wrapper.find('.review-form textarea').setValue('不错')
    await wrapper.find('.review-submit-btn').trigger('click')
    await flushPromises()
    expect(mockedSubmit).toHaveBeenCalledTimes(1)
    const command = mockedSubmit.mock.calls[0][0]
    expect(command.productId).toBe(15)
    expect(command.score).toBe(4)
    expect(command.comment).toBe('不错')
    expect(command.requestId).toBeTruthy()
    expect(mockedStats).toHaveBeenCalledTimes(2)
    expect(mockedList).toHaveBeenCalledTimes(2)
  })

  it('无资格被拒（1011C0006）：区块内业务可读文案展示，负向演示路径（S2-10）', async () => {
    mockedSubmit.mockRejectedValue(new ApiError('1011C0006', REVIEW_ELIGIBILITY_DENIED_TIP))
    const { wrapper } = await mountPage()
    // 无资格者表单照常可见可提交（服务端拒绝后展示理由——防刷评门槛在服务端）
    await wrapper.find('.review-form .el-rate').findAll('.el-rate__item')[2].trigger('click')
    await wrapper.find('.review-submit-btn').trigger('click')
    await flushPromises()
    expect(wrapper.find('.review-error-tip').text()).toBe(REVIEW_ELIGIBILITY_DENIED_TIP)
  })

  it('未选评分提交：前端拦截不发请求（1011C0005 同义拦截），提示业务可读', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('.review-submit-btn').trigger('click')
    await flushPromises()
    expect(mockedSubmit).not.toHaveBeenCalled()
    expect(wrapper.find('.review-error-tip').text()).toBe(REVIEW_SCORE_INVALID_TIP)
  })

  it('隐藏按钮仅运营档渲染（普通档零按钮；体验层 + 服务端 delivery.governance 把关）', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.findAll('.review-hide-btn')).toHaveLength(0)
    setActorMode('operator')
    const { wrapper: op } = await mountPage()
    expect(op.findAll('.review-hide-btn')).toHaveLength(2)
    // 运营档同样拉统计与列表（看统计/列表 ✓）
    expect(mockedStats).toHaveBeenCalled()
    expect(mockedList).toHaveBeenCalled()
  })

  it('运营隐藏：confirm 固定文案 → 调 G-EV1 → 重拉统计/列表（S2-12）', async () => {
    setActorMode('operator')
    mockedHide.mockResolvedValue({ reviewId: 2, hidden: true })
    const { wrapper } = await mountPage()
    await wrapper.findAll('.review-hide-btn')[0].trigger('click')
    await flushPromises()
    const { ElMessageBox } = await import('element-plus')
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      REVIEW_HIDE_CONFIRM_TEXT, expect.anything(), expect.anything())
    expect(mockedHide).toHaveBeenCalledWith(2)
    expect(mockedStats).toHaveBeenCalledTimes(2)
    expect(mockedList).toHaveBeenCalledTimes(2)
  })
})
