import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import PortalHomeView from './PortalHomeView.vue'
import { listCategories, searchProducts, type CatalogProduct, type CategoryNode } from '../../api/catalog'
import { ApiError, setDemoSubject } from '../../api/client'
import { SEARCH_EMPTY_TIP, PORTAL_BROWSE_DESC } from '../../constants/catalog'

/**
 * 门户首页测试（WBS-3.7.2 hifi §3.1 / §7 T3~T5）：
 * - 数据加载：R5 + R6 并行（第 1 页、12/页）；浏览只读（无写端点调用）；
 * - 检索：关键词 / 类目树 / 重置 / 页码重置；无本地过滤（请求参数与输入一致，结果原样渲染）；
 * - 列表：卡片字段齐全、免费 tag、详情导航、空态与错误 toast。
 */
vi.mock('../../api/catalog', () => ({
  listCategories: vi.fn(),
  searchProducts: vi.fn(),
}))

const mockedCategories = vi.mocked(listCategories)
const mockedSearch = vi.mocked(searchProducts)

function category(over: Partial<CategoryNode> = {}): CategoryNode {
  return { categoryCode: 'finance', categoryName: '金融', children: [], ...over }
}

function product(over: Partial<CatalogProduct> = {}): CatalogProduct {
  return {
    productId: 15,
    productName: 'W353 Demo API Feed',
    intro: '面向普惠金融场景的目录元数据产品',
    productType: 'API接口',
    pricingModel: '按次',
    categoryCode: 'finance',
    categoryName: '金融',
    providerSubjectNo: 'S20260925000001',
    listedAt: '2026-10-20T22:02:01',
    ...over,
  }
}

function page<T>(list: T[], over: Partial<{ total: number; pageNum: number; pageSize: number; totalPages: number }> = {}) {
  return { list, total: list.length, pageNum: 1, pageSize: 12, totalPages: 1, ...over }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/portal', name: 'portal-home', component: PortalHomeView },
      { path: '/portal/products/:productId', name: 'portal-product-detail', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push('/portal')
  await router.isReady()
  const wrapper = mount(PortalHomeView, {
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
  for (const fn of [mockedCategories, mockedSearch]) fn.mockReset()
  setDemoSubject('S20260925000001')
  mockedCategories.mockResolvedValue([
    category({ children: [category({ categoryCode: 'finance-banking', categoryName: '银行保险' })] }),
    category({ categoryCode: 'transport', categoryName: '交通运输' }),
  ])
  mockedSearch.mockResolvedValue(page([product()]))
})

describe('数据加载与只读（T4 / T5 / B1-3）', () => {
  it('进入页面并行加载 R5 + R6：第 1 页、12/页，仅读面请求（浏览只读）', async () => {
    await mountPage()
    expect(mockedCategories).toHaveBeenCalledTimes(1)
    expect(mockedSearch).toHaveBeenCalledWith({
      pageNum: 1, pageSize: 12, keyword: '', categoryCode: '',
    })
    expect(mockedSearch).toHaveBeenCalledTimes(1)
  })

  it('页头标题与说明（Q5-A：无推送 / 消息承诺）', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain('数据产品门户')
    expect(wrapper.text()).toContain(PORTAL_BROWSE_DESC)
  })
})

describe('检索区（T4 / Q4-A 无新增检索语义）', () => {
  it('类目树渲染两级结构；从第 2 页点击类目仍以页码 1 检索', async () => {
    mockedSearch.mockResolvedValue(page([product({ categoryCode: 'finance' })], { total: 25, totalPages: 3 }))
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain('金融')
    expect(wrapper.text()).toContain('银行保险')
    expect(wrapper.text()).toContain('交通运输')

    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith({
      pageNum: 2, pageSize: 12, keyword: '', categoryCode: '',
    })

    await wrapper.findAll('.el-tree-node__content')[0].trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith({
      pageNum: 1, pageSize: 12, keyword: '', categoryCode: 'finance',
    })
  })

  it('「全部」清除类目过滤并以空 categoryCode、页码 1 重新检索', async () => {
    mockedSearch.mockResolvedValue(page([product({ categoryCode: 'finance' })], { total: 25, totalPages: 3 }))
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tree-node__content')[0].trigger('click')
    await flushPromises()
    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()
    await wrapper.find('.category-all-btn').trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith({
      pageNum: 1, pageSize: 12, keyword: '', categoryCode: '',
    })
  })

  it('关键词查询与重置：从第 2 页触发均回到页码 1，重置清空关键词 / 类目', async () => {
    mockedSearch.mockResolvedValue(page([product({ categoryCode: 'finance' })], { total: 25, totalPages: 3 }))
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tree-node__content')[0].trigger('click')
    await flushPromises()
    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith({
      pageNum: 2, pageSize: 12, keyword: '', categoryCode: 'finance',
    })

    await wrapper.find('.keyword-input input').setValue(' 普惠 ')
    await wrapper.find('.search-btn').trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith({
      pageNum: 1, pageSize: 12, keyword: '普惠', categoryCode: 'finance',
    })

    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith({
      pageNum: 2, pageSize: 12, keyword: '普惠', categoryCode: 'finance',
    })

    await wrapper.find('.reset-btn').trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith({
      pageNum: 1, pageSize: 12, keyword: '', categoryCode: '',
    })
  })

  it('翻页：以新页码与 12/页重新拉取并渲染新页（服务端分页）', async () => {
    mockedSearch.mockResolvedValue(page([product({ productName: '第 1 页产品' })], { total: 25, totalPages: 3 }))
    const { wrapper } = await mountPage()
    expect(mockedSearch).toHaveBeenCalledWith({ pageNum: 1, pageSize: 12, keyword: '', categoryCode: '' })

    mockedSearch.mockResolvedValue(page([product({ productId: 16, productName: '第 2 页产品' })], {
      total: 25, pageNum: 2, totalPages: 3,
    }))
    await wrapper.find('.pager .btn-next').trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith({ pageNum: 2, pageSize: 12, keyword: '', categoryCode: '' })
    expect(wrapper.text()).toContain('第 2 页产品')
  })

  it('无本地过滤：接口返回与关键词不完全匹配的集合原样渲染（请求参数与输入一致）', async () => {
    mockedSearch.mockResolvedValue(page([
      product({ productId: 21, productName: '交通流量预测服务', intro: '无关简介' }),
    ]))
    const { wrapper } = await mountPage()
    await wrapper.find('.keyword-input input').setValue('金融')
    await wrapper.find('.search-btn').trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenLastCalledWith({
      pageNum: 1, pageSize: 12, keyword: '金融', categoryCode: '',
    })
    expect(wrapper.text()).toContain('交通流量预测服务')
  })

  it('空态：展示 SEARCH_EMPTY_TIP（空态不是报错）', async () => {
    mockedSearch.mockResolvedValue(page([]))
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain(SEARCH_EMPTY_TIP)
    expect(document.body.textContent).not.toContain('请求失败')
  })

  it('检索错误：服务端文案原样 toast，列表保持空态', async () => {
    mockedSearch.mockRejectedValue(new ApiError('1007C0006', '主体未入驻或不存在，无法使用统一目录服务'))
    const { wrapper } = await mountPage()
    expect(document.body.textContent).toContain('主体未入驻或不存在，无法使用统一目录服务')
    expect(wrapper.text()).toContain(SEARCH_EMPTY_TIP)
  })
})

describe('产品卡片列表（T5）', () => {
  it('卡片字段齐全：名称 / 定价 / 简介 / 形态 / 类目 / 提供方主体编号 / 上架时间', async () => {
    const { wrapper } = await mountPage()
    const card = wrapper.find('.product-card')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('W353 Demo API Feed')
    expect(card.text()).toContain('按次')
    expect(card.text()).toContain('面向普惠金融场景的目录元数据产品')
    expect(card.text()).toContain('API接口')
    expect(card.text()).toContain('金融')
    expect(card.text()).toContain('S20260925000001')
    expect(card.text()).toContain('2026-10-20T22:02:01')
  })

  it('免费档 tag 为 success 色，付费档为 info 色（仅视觉，不含业务判定）', async () => {
    mockedSearch.mockResolvedValue(page([
      product({ productId: 10, productName: '免费体验版', pricingModel: '免费' }),
      product({ productId: 13, productName: '包月产品', pricingModel: '包月' }),
    ]))
    const { wrapper } = await mountPage()
    const tags = wrapper.findAll('.product-card .pricing-tag .el-tag')
    expect(tags.length).toBe(2)
    expect(tags[0].classes()).toContain('el-tag--success')
    expect(tags[1].classes()).toContain('el-tag--info')
  })

  it('空字段显示占位符 "—"（简介 / 类目 / 上架时间）', async () => {
    mockedSearch.mockResolvedValue(page([
      product({ intro: null, categoryName: null, listedAt: null }),
    ]))
    const { wrapper } = await mountPage()
    const card = wrapper.find('.product-card')
    expect(card.text()).toContain('—')
    expect(card.text()).not.toContain('null')
  })

  it('R6 返回集合原样渲染（P0~P3 四档齐全，不多不少）', async () => {
    mockedSearch.mockResolvedValue(page([
      product({ productId: 10, productName: 'P0 免费', pricingModel: '免费' }),
      product({ productId: 11, productName: 'P1 按次', pricingModel: '按次' }),
      product({ productId: 12, productName: 'P2 包月', pricingModel: '包月' }),
      product({ productId: 13, productName: 'P3 交易额分成', pricingModel: '交易额分成' }),
    ]))
    const { wrapper } = await mountPage()
    expect(wrapper.findAll('.product-card').length).toBe(4)
    expect(wrapper.text()).toContain('P0 免费')
    expect(wrapper.text()).toContain('P1 按次')
    expect(wrapper.text()).toContain('P2 包月')
    expect(wrapper.text()).toContain('P3 交易额分成')
  })

  it('「查看详情」导航到门户详情深链 /portal/products/:productId', async () => {
    const { wrapper, router } = await mountPage()
    await wrapper.find('.detail-btn').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('portal-product-detail')
    expect(router.currentRoute.value.params.productId).toBe('15')
  })
})
