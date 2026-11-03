import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import BillingManageView from './BillingManageView.vue'
import { listGovernanceBills, listGovernanceMeteringFlows, outbillBill, type BillSummary, type MeteringFlowRow } from '../../api/billing'
import { listGovernanceOrders, type OrderSummary } from '../../api/order'
import { setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'

/**
 * 计费管理页测试（WBS-3.7.7 hifi §3 P1/P2/P7/P8/P9；§9 T1/T2/T8/T9/T10）：
 * - T1 账单管理：列表 / 七维过滤（含状态）/ 重置回第 1 页 / 空态 / 失败态 + 重试；
 * - T2 手动出账：请求体（合约 + 默认上一自然月 + 分成基数）/ 409 与 400 原样展示且弹窗不关闭 / 成功跳详情；
 * - T8 计量流水：九列渲染 / 合约·主体·源类型过滤 / 空态 / 1000C0001 原样展示；
 * - T9 订单检索：四过滤触发 R-OR3 / 行内零详情链接 / 1011C0003 原样展示；
 * - T10 费率视图：四档卡片 + 目录指路 + 只读结构（零输入框零提交按钮）。
 */
vi.mock('../../api/billing', () => ({
  listGovernanceBills: vi.fn(),
  listGovernanceMeteringFlows: vi.fn(),
  outbillBill: vi.fn(),
}))
vi.mock('../../api/order', () => ({ listGovernanceOrders: vi.fn() }))

const mockedBills = vi.mocked(listGovernanceBills)
const mockedFlows = vi.mocked(listGovernanceMeteringFlows)
const mockedOutbill = vi.mocked(outbillBill)
const mockedOrders = vi.mocked(listGovernanceOrders)

function bill(over: Partial<BillSummary> = {}): BillSummary {
  return {
    billNo: 'BL000001',
    billType: 'NORMAL',
    contractNo: 'CO000001',
    requesterNo: 'S20260925000002',
    providerNo: 'S20260925000001',
    productId: 15,
    productName: '气象数据 API',
    period: '2026-10',
    pricingModel: 'PER_CALL',
    amount: '30.00',
    status: 'ISSUED',
    correctionOfBillNo: null,
    createdAt: '2026-11-01T00:10:00',
    myRole: null,
    ...over,
  }
}

function flow(over: Partial<MeteringFlowRow> = {}): MeteringFlowRow {
  return {
    id: 1,
    contractNo: 'CO000001',
    requesterNo: 'S20260925000002',
    sourceType: 'GATEWAY_API',
    sourceNo: 'evt-1',
    action: 'gateway.delivery.metering',
    resource: 'delivery-catalog-products GET /x',
    occurredAt: '2026-10-01T09:00:00',
    createdAt: '2026-10-01T09:00:01',
    ...over,
  }
}

function order(over: Partial<OrderSummary> = {}): OrderSummary {
  return {
    orderNo: 'OD000001',
    status: 'COMPLETED',
    contractNo: 'CO000001',
    contractStatus: 'EFFECTIVE',
    productId: 15,
    productName: '气象数据 API',
    providerSubjectNo: 'S20260925000001',
    requesterSubjectNo: 'S20260925000002',
    pricingModel: 'PER_CALL',
    priceAmount: '3.00',
    createdAt: '2026-10-01T10:00:00',
    ...over,
  }
}

function page<T>(list: T[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/billing', name: 'billing', component: BillingManageView },
      { path: '/billing/bills/:billNo', name: 'billing-detail', component: { template: '<div />' } },
      { path: '/catalog/products', name: 'catalog-products', component: { template: '<div />' } },
      { path: '/dashboard', name: 'dashboard', component: { template: '<div />' } },
    ],
  })
  await router.push('/billing')
  await router.isReady()
  const wrapper = mount(BillingManageView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

async function openTab(wrapper: ReturnType<typeof mount>, label: string) {
  const tab = wrapper.findAll('.el-tabs__item').find((item) => item.text().includes(label))
  expect(tab, `页签「${label}」应存在`).toBeTruthy()
  await tab!.trigger('click')
  await flushPromises()
}

/** 默认账期 = 上一自然月（hifi §3 P2）。 */
function lastMonth(): string {
  const now = new Date()
  const date = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.clearAllMocks()
  setDemoSubject('S20260925000001')
  setActorMode('operator')
  mockedBills.mockResolvedValue(page([bill()]))
  mockedFlows.mockResolvedValue(page([flow()]))
  mockedOrders.mockResolvedValue(page([order()]))
})

describe('T1 账单管理（hifi §3 P1）', () => {
  it('挂载即拉取第 1 页，渲染账单号/类型/合约/需方/供方/产品/账期/定价档/金额/状态', async () => {
    const { wrapper } = await mountView()
    expect(mockedBills).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 10 }))
    const text = wrapper.text()
    expect(text).toContain('BL000001')
    expect(text).toContain('普通账单')
    expect(text).toContain('CO000001')
    expect(text).toContain('S20260925000002')
    expect(text).toContain('S20260925000001')
    expect(text).toContain('气象数据 API')
    expect(text).toContain('2026-10')
    expect(text).toContain('按次')
    expect(text).toContain('30.00')
    expect(text).toContain('已开具')
  })

  it('状态过滤 + 查询：参数透传且重置到第 1 页', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('.filter-status').trigger('click')
    await flushPromises()
    const option = [...document.querySelectorAll('.el-select-dropdown__item')]
      .find((el) => el.textContent?.includes('已冻结'))
    expect(option).toBeTruthy()
    ;(option as HTMLElement).click()
    await flushPromises()
    await wrapper.find('.bill-search-btn').trigger('click')
    await flushPromises()
    expect(mockedBills).toHaveBeenLastCalledWith(expect.objectContaining({
      pageNum: 1, status: 'FROZEN',
    }))
  })

  it('合约号过滤：值透传为 contractNo', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('.filter-contract input').setValue('CO000099')
    await wrapper.find('.bill-search-btn').trigger('click')
    await flushPromises()
    expect(mockedBills).toHaveBeenLastCalledWith(expect.objectContaining({ contractNo: 'CO000099' }))
  })

  it('重置：清空过滤并回第 1 页', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('.filter-contract input').setValue('CO000099')
    await wrapper.find('.bill-reset-btn').trigger('click')
    await flushPromises()
    expect(mockedBills).toHaveBeenLastCalledWith(expect.objectContaining({
      pageNum: 1, contractNo: undefined, status: undefined,
    }))
  })

  it('空态文案：暂无账单，可调整过滤条件或发起手动出账', async () => {
    mockedBills.mockResolvedValue(page([]))
    const { wrapper } = await mountView()
    expect(wrapper.text()).toContain('暂无账单，可调整过滤条件或发起手动出账')
  })

  it('失败态：服务端 message 原样展示 + 重试按钮重拉', async () => {
    mockedBills.mockRejectedValueOnce(new Error('参数格式不合法'))
    const { wrapper } = await mountView()
    expect(wrapper.find('.list-error').text()).toContain('参数格式不合法')
    await wrapper.find('.retry-btn').trigger('click')
    await flushPromises()
    expect(mockedBills).toHaveBeenCalledTimes(2)
  })

  it('账单号列指向运营账单详情深链', async () => {
    const { wrapper, router } = await mountView()
    await wrapper.find('.bill-link').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/billing/bills/BL000001')
  })

  it('未知状态值原样显示（§7：不隐藏不猜测）', async () => {
    mockedBills.mockResolvedValue(page([bill({ status: 'SETTLED' })]))
    const { wrapper } = await mountView()
    expect(wrapper.text()).toContain('SETTLED')
  })

  it('分页档位 10/20/50：切换为 50 后回第 1 页重新请求（§3 P1）', async () => {
    mockedBills.mockResolvedValue({ list: [bill()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const { wrapper } = await mountView()
    const pagination = wrapper.findComponent({ name: 'ElPagination' })
    expect(pagination.props('pageSizes')).toEqual([10, 20, 50])
    pagination.vm.$emit('size-change', 50)
    await flushPromises()
    expect(mockedBills).toHaveBeenLastCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 50 }))
  })
})

describe('T2 手动出账（hifi §3 P2）', () => {
  it('打开弹窗 → 提交体含合约号 + 默认上一自然月账期', async () => {
    mockedOutbill.mockResolvedValue({ billNo: 'BL000009' } as never)
    const { wrapper, router } = await mountView()
    await wrapper.find('.outbill-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.outbill-contract input').setValue('CO000001')
    await wrapper.find('.outbill-submit').trigger('click')
    await flushPromises()
    expect(mockedOutbill).toHaveBeenCalledWith({
      contractNo: 'CO000001',
      period: lastMonth(),
      agreedTransactionAmount: undefined,
    })
    expect(router.currentRoute.value.path).toBe('/billing/bills/BL000009')
  })

  it('分成档：填写约定交易额后随请求体提交（字符串）', async () => {
    mockedOutbill.mockResolvedValue({ billNo: 'BL000010' } as never)
    const { wrapper } = await mountView()
    await wrapper.find('.outbill-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.outbill-contract input').setValue('CO000001')
    await wrapper.find('.outbill-base input').setValue('10000.00')
    await wrapper.find('.outbill-submit').trigger('click')
    await flushPromises()
    expect(mockedOutbill).toHaveBeenCalledWith(expect.objectContaining({
      agreedTransactionAmount: '10000.00',
    }))
  })

  it('合约号留空：前端拦截（零请求）', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('.outbill-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.outbill-submit').trigger('click')
    await flushPromises()
    expect(mockedOutbill).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请填写合约号')
  })

  it('409 重复出账：弹窗内原样展示服务端文案且弹窗不关闭', async () => {
    mockedOutbill.mockRejectedValue(new Error('该合约该周期账单已存在，请勿重复出账'))
    const { wrapper } = await mountView()
    await wrapper.find('.outbill-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.outbill-contract input').setValue('CO000001')
    await wrapper.find('.outbill-submit').trigger('click')
    await flushPromises()
    expect(wrapper.find('.outbill-error').text()).toContain('该合约该周期账单已存在，请勿重复出账')
    expect(wrapper.find('.outbill-submit').exists()).toBe(true)
  })

  it('400 缺分成基数：原样展示 1011C0013 文案', async () => {
    mockedOutbill.mockRejectedValue(new Error('交易额分成档须提供约定交易额'))
    const { wrapper } = await mountView()
    await wrapper.find('.outbill-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.outbill-contract input').setValue('CO000001')
    await wrapper.find('.outbill-submit').trigger('click')
    await flushPromises()
    expect(wrapper.find('.outbill-error').text()).toContain('交易额分成档须提供约定交易额')
  })

  it('免费档 409（1011C0015）：原样展示，零跳转', async () => {
    mockedOutbill.mockRejectedValue(new Error('合约不满足出账条件（免费档不产生账单）'))
    const { wrapper, router } = await mountView()
    await wrapper.find('.outbill-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.outbill-contract input').setValue('CO000001')
    await wrapper.find('.outbill-submit').trigger('click')
    await flushPromises()
    expect(wrapper.find('.outbill-error').text()).toContain('免费档不产生账单')
    expect(router.currentRoute.value.path).toBe('/billing')
  })

  it('说明卡常显：四档出账口径文案', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('.outbill-btn').trigger('click')
    await flushPromises()
    const text = document.body.textContent ?? ''
    expect(text).toContain('按次 = 事件数 × 次单价')
    expect(text).toContain('包月 = 整月周期费')
    expect(text).toContain('交易额分成 = 约定交易额 × 费率')
    expect(text).toContain('免费档不出账单')
  })
})

describe('T8 计量流水（hifi §3 P7）', () => {
  it('切到计量流水页签：拉取全量流水并渲染九列', async () => {
    const { wrapper } = await mountView()
    await openTab(wrapper, '计量流水')
    expect(mockedFlows).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 10 }))
    const text = wrapper.text()
    expect(text).toContain('evt-1')
    expect(text).toContain('网关 API 调用')
    expect(text).toContain('gateway.delivery.metering')
    expect(text).toContain('delivery-catalog-products GET /x')
    expect(text).toContain('2026-10-01T09:00:00')
    expect(text).toContain('本页为运营方全量逐条流水口径')
  })

  it('合约 / 主体 / 源类型过滤：三参数透传', async () => {
    const { wrapper } = await mountView()
    await openTab(wrapper, '计量流水')
    await wrapper.find('.metering-contract input').setValue('CO000001')
    await wrapper.find('.metering-requester input').setValue('S20260925000002')
    await wrapper.find('.metering-source-type').trigger('click')
    await flushPromises()
    const option = [...document.querySelectorAll('.el-select-dropdown__item')]
      .find((el) => el.textContent?.includes('沙箱任务'))
    expect(option).toBeTruthy()
    ;(option as HTMLElement).click()
    await flushPromises()
    await wrapper.find('.metering-search-btn').trigger('click')
    await flushPromises()
    expect(mockedFlows).toHaveBeenLastCalledWith(expect.objectContaining({
      contractNo: 'CO000001', requesterNo: 'S20260925000002', sourceType: 'SANDBOX_JOB',
    }))
  })

  it('时间区间控件渲染（from/to 取值口径由 api/billing.spec 锚定）', async () => {
    const { wrapper } = await mountView()
    await openTab(wrapper, '计量流水')
    expect(wrapper.find('.metering-time-range').exists()).toBe(true)
  })

  it('空态：暂无计量流水', async () => {
    mockedFlows.mockResolvedValue(page([]))
    const { wrapper } = await mountView()
    await openTab(wrapper, '计量流水')
    expect(wrapper.text()).toContain('暂无计量流水')
  })

  it('1000C0001 参数非法：原样展示服务端文案', async () => {
    mockedFlows.mockRejectedValue(new Error('参数格式不合法'))
    const { wrapper } = await mountView()
    await openTab(wrapper, '计量流水')
    await flushPromises()
    expect(wrapper.find('.metering-error').text()).toContain('参数格式不合法')
  })
})

describe('T9 订单检索（hifi §3 P8）', () => {
  it('切到订单检索页签：调用 R-OR3 并按行渲染', async () => {
    const { wrapper } = await mountView()
    await openTab(wrapper, '订单检索')
    expect(mockedOrders).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 10 }))
    const text = wrapper.text()
    expect(text).toContain('OD000001')
    expect(text).toContain('已完成（已订购）')
    expect(text).toContain('运营检索为全量口径')
  })

  it('三次过滤（产品 / 需方 / 状态）分别触发且参数正确', async () => {
    const { wrapper } = await mountView()
    await openTab(wrapper, '订单检索')
    await wrapper.find('.order-product input').setValue('15')
    await wrapper.find('.order-search-btn').trigger('click')
    await flushPromises()
    expect(mockedOrders).toHaveBeenLastCalledWith(expect.objectContaining({ productId: 15 }))

    await wrapper.find('.order-requester input').setValue('S20260925000002')
    await wrapper.find('.order-search-btn').trigger('click')
    await flushPromises()
    expect(mockedOrders).toHaveBeenLastCalledWith(expect.objectContaining({
      requesterNo: 'S20260925000002',
    }))

    await wrapper.find('.order-status').trigger('click')
    await flushPromises()
    const option = [...document.querySelectorAll('.el-select-dropdown__item')]
      .find((el) => el.textContent?.includes('已完成（已订购）'))
    expect(option).toBeTruthy()
    ;(option as HTMLElement).click()
    await flushPromises()
    await wrapper.find('.order-search-btn').trigger('click')
    await flushPromises()
    expect(mockedOrders).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'COMPLETED' }))
  })

  it('行内零详情链接（运营无订单详情读权，hifi E0-2）', async () => {
    const { wrapper } = await mountView()
    await openTab(wrapper, '订单检索')
    expect(wrapper.find('.order-table a').exists()).toBe(false)
  })

  it('1011C0003 原样展示', async () => {
    mockedOrders.mockRejectedValue(new Error('无权限执行该操作'))
    const { wrapper } = await mountView()
    await openTab(wrapper, '订单检索')
    await flushPromises()
    expect(wrapper.find('.order-error').text()).toContain('无权限执行该操作')
  })
})

describe('T10 费率视图（hifi §3 P9）', () => {
  it('四档口径卡片齐备 + 快照来源说明', async () => {
    const { wrapper } = await mountView()
    await openTab(wrapper, '费率视图')
    const text = wrapper.text()
    expect(text).toContain('免费')
    expect(text).toContain('按次')
    expect(text).toContain('包月')
    expect(text).toContain('交易额分成')
    expect(text).toContain('定价来源 = 目录域产品定价')
    expect(text).toContain('改价不追溯已出账单')
    expect(wrapper.findAll('.rate-card')).toHaveLength(4)
  })

  it('自动出账说明常显（E0-3：界面只说明、不提供改配置入口）', async () => {
    const { wrapper } = await mountView()
    await openTab(wrapper, '费率视图')
    expect(wrapper.find('.auto-outbill-hint').text()).toContain('每月 1 日 00:10 自动出上月账')
  })

  it('「前往产品上架页」指向 /catalog/products（仅指路，不内嵌编辑）', async () => {
    const { wrapper } = await mountView()
    await openTab(wrapper, '费率视图')
    const link = wrapper.find('.rates-pane a')
    expect(link.attributes('href')).toBe('/catalog/products')
  })

  it('只读结构：该页签零输入框、零提交按钮', async () => {
    const { wrapper } = await mountView()
    await openTab(wrapper, '费率视图')
    expect(wrapper.find('.rates-pane input').exists()).toBe(false)
    expect(wrapper.find('.rates-pane button').exists()).toBe(false)
    expect(wrapper.find('.rates-pane textarea').exists()).toBe(false)
  })
})
