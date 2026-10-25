import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import DeliveryGovernanceView from './DeliveryGovernanceView.vue'
import {
  listGovernanceSandboxes,
  getGovernanceSandbox,
  listSandboxResults,
  listGovernanceReviews,
  reviewGovernanceResult,
  listGovernanceDeliveries,
  getGovernanceDelivery,
  listGovernanceDeliveryActionLogs,
  type SandboxRecord,
  type SandboxResultItem,
  type ReviewRecord,
  type DeliveryRecord,
  type ActionLogItem,
} from '../../api/delivery'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'
import { GOVERNANCE_READ_TRACE_HINT, REVIEW_IMMUTABLE_HINT } from '../../constants/delivery'

/**
 * 交付治理页测试（WBS-3.5.6 hifi §6.4 / §7 T11/T12/T13）：
 * - T11 Tab1 导出审批：列表 / 治理详情 / 审核弹窗（驳回理由必填 + 256 上限 + 通过免理由）/
 *   已出结论行禁用 + 不可改提示 / 审核留痕轮次列 / 读即留痕提示常驻；
 * - T12 Tab2 交付记录治理：列表 / 详情无下载按钮 / 读即留痕提示；
 * - T13 动作留痕区（G7b 治理路径）：渲染 / 分页 / 空态。
 */
vi.mock('../../api/delivery', () => ({
  listGovernanceSandboxes: vi.fn(),
  getGovernanceSandbox: vi.fn(),
  listSandboxResults: vi.fn(),
  listGovernanceReviews: vi.fn(),
  reviewGovernanceResult: vi.fn(),
  listGovernanceDeliveries: vi.fn(),
  getGovernanceDelivery: vi.fn(),
  listGovernanceDeliveryActionLogs: vi.fn(),
}))

const mockedList = vi.mocked(listGovernanceSandboxes)
const mockedGet = vi.mocked(getGovernanceSandbox)
const mockedResults = vi.mocked(listSandboxResults)
const mockedReviews = vi.mocked(listGovernanceReviews)
const mockedReview = vi.mocked(reviewGovernanceResult)
const mockedDeliveries = vi.mocked(listGovernanceDeliveries)
const mockedDeliveryGet = vi.mocked(getGovernanceDelivery)
const mockedActionLogs = vi.mocked(listGovernanceDeliveryActionLogs)

function task(over: Partial<SandboxRecord> = {}): SandboxRecord {
  return {
    sandboxNo: 'SB000004',
    contractNo: 'CO000026',
    productId: 13,
    productName: '普惠金融数据服务',
    requesterNo: 'S20260922000001',
    providerNo: 'S20260925000001',
    status: 'STOPPED',
    cpus: 1,
    memoryMb: 512,
    ttlSeconds: 7200,
    expiresAt: '2026-10-24T21:52:29',
    dataFileCount: 1,
    closeReason: 'REQUESTER_CLOSE',
    createdAt: '2026-10-24T21:53:29',
    ...over,
  }
}

function result(over: Partial<SandboxResultItem> = {}): SandboxResultItem {
  return {
    fileName: 'dataset-profile.md',
    sizeBytes: 182,
    checksum: '9388588a',
    createdAt: '2026-10-24T21:57:03',
    status: 'PENDING',
    conclusion: null,
    rejectReason: null,
    reviewRound: 1,
    ...over,
  }
}

function review(over: Partial<ReviewRecord> = {}): ReviewRecord {
  return {
    sandboxNo: 'SB000004',
    fileName: 'dataset-profile.md',
    reviewRound: 1,
    conclusion: 'REJECTED',
    reason: 'output contains unmasked sample field',
    reviewedBy: 'S20260914000001',
    createdAt: '2026-10-24T21:56:59',
    ...over,
  }
}

function delivery(over: Partial<DeliveryRecord> = {}): DeliveryRecord {
  return {
    deliveryNo: 'DL000009',
    contractNo: 'CO000026',
    productId: 13,
    productName: '普惠金融数据服务',
    deliveryType: 'SANDBOX_EXPORT',
    requesterNo: 'S20260922000001',
    providerNo: 'S20260925000001',
    fileId: null,
    fileName: 'dataset-profile.md',
    fileSize: 182,
    packageId: 'DL000009.sm4',
    checksum: 'b78f346b',
    result: 'SUCCESS',
    createdAt: '2026-10-24T21:57:01',
    ...over,
  }
}

function actionLog(over: Partial<ActionLogItem> = {}): ActionLogItem {
  return {
    id: 57,
    deliveryNo: null,
    contractNo: 'CO000026',
    operatorNo: 'S-evil',
    action: 'delivery.denied',
    result: 'DENIED',
    reasonCode: '1009C0003',
    createdAt: '2026-10-25T09:53:07',
    ...over,
  }
}

function page<T>(list: T[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage(route = '/delivery-governance') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/delivery-governance',
        name: 'delivery-governance',
        component: DeliveryGovernanceView,
      },
    ],
  })
  await router.push(route)
  await router.isReady()
  const wrapper = mount(DeliveryGovernanceView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return wrapper
}

function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((b) => b.text().includes(text))
}

async function openDetail(wrapper: ReturnType<typeof mount>) {
  mockedGet.mockResolvedValue(task())
  mockedResults.mockResolvedValue([result()])
  mockedReviews.mockResolvedValue(page([review()]))
  mockedActionLogs.mockResolvedValue(page([actionLog()]))
  await button(wrapper, '治理详情')?.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  for (const fn of [
    mockedList,
    mockedGet,
    mockedResults,
    mockedReviews,
    mockedReview,
    mockedDeliveries,
    mockedDeliveryGet,
    mockedActionLogs,
  ]) {
    fn.mockReset()
  }
  setDemoSubject('S20260914000001')
  setActorMode('operator')
  if (!isDemoAuthed()) {
    signInDemo()
  }
  mockedList.mockResolvedValue(page([task()]))
  mockedDeliveries.mockResolvedValue(page([delivery()]))
})

describe('Tab1 导出审批（T11）', () => {
  it('读即留痕提示常驻 + 治理列表装载', async () => {
    await mountPage()
    expect(document.body.textContent).toContain(GOVERNANCE_READ_TRACE_HINT)
    expect(mockedList).toHaveBeenCalledWith({ pageNum: 1, pageSize: 10 })
    expect(document.body.textContent).toContain('SB000004')
  })

  it('治理详情：结果物 + 审核留痕轮次列 + 动作留痕区（G7b）可见', async () => {
    const wrapper = await mountPage()
    await openDetail(wrapper)
    expect(mockedGet).toHaveBeenCalledWith('SB000004')
    expect(document.body.textContent).toContain('dataset-profile.md')
    expect(document.body.textContent).toContain('轮次')
    expect(mockedReviews).toHaveBeenCalledWith('SB000004', { pageNum: 1, pageSize: 10 })
    expect(mockedActionLogs).toHaveBeenCalledWith({ contractNo: 'CO000026', pageNum: 1, pageSize: 10 })
    expect(document.body.textContent).toContain('S-evil')
    expect(document.body.textContent).toContain('1009C0003')
  })

  it('审核弹窗：结论不可改提示常驻；通过免理由可直接提交', async () => {
    const wrapper = await mountPage()
    await openDetail(wrapper)
    mockedReview.mockResolvedValue(result({ status: 'APPROVED', conclusion: 'APPROVED' }))
    await button(wrapper, '审核')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(REVIEW_IMMUTABLE_HINT)
    mockedReview.mockClear()
    const radios = wrapper.findAll('.el-dialog .el-radio')
    await radios[0].find('input').setValue()
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedReview).toHaveBeenCalledTimes(1)
    const [sandboxNo, fileName, command] = mockedReview.mock.calls[0]
    expect(sandboxNo).toBe('SB000004')
    expect(fileName).toBe('dataset-profile.md')
    expect(command.conclusion).toBe('APPROVE')
  })

  it('驳回时理由必填：留空提交 = 零请求；256 上限输入框属性', async () => {
    const wrapper = await mountPage()
    await openDetail(wrapper)
    await button(wrapper, '审核')?.trigger('click')
    await flushPromises()
    const radios = wrapper.findAll('.el-dialog .el-radio')
    await radios[1].find('input').setValue()
    const textarea = wrapper.find('.el-dialog textarea')
    expect(textarea.attributes('maxlength')).toBe('256')
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedReview).not.toHaveBeenCalled()
    await textarea.setValue('含未脱敏字段')
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedReview).toHaveBeenCalledTimes(1)
    expect(mockedReview.mock.calls[0][2]).toEqual({
      conclusion: 'REJECT',
      reason: '含未脱敏字段',
    })
  })

  it('已出结论行：审核按钮禁用（结论一经作出不可修改）', async () => {
    const wrapper = await mountPage()
    mockedGet.mockResolvedValue(task())
    mockedResults.mockResolvedValue([
      result({ status: 'APPROVED', conclusion: 'APPROVED', reviewRound: 1 }),
    ])
    mockedReviews.mockResolvedValue(page([]))
    mockedActionLogs.mockResolvedValue(page([]))
    await button(wrapper, '治理详情')?.trigger('click')
    await flushPromises()
    const reviewBtn = wrapper.findAll('button').find((b) => b.text().trim() === '审核')
    expect(reviewBtn?.attributes('disabled')).toBeDefined()
  })

  it('审核被拒（1009C0016）：文案原样', async () => {
    const wrapper = await mountPage()
    await openDetail(wrapper)
    mockedReview.mockRejectedValue(new ApiError('1009C0016', '结果物当前状态不允许该操作'))
    await button(wrapper, '审核')?.trigger('click')
    await flushPromises()
    const radios = wrapper.findAll('.el-dialog .el-radio')
    await radios[0].find('input').setValue()
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('结果物当前状态不允许该操作')
  })
})

describe('Tab2 交付记录治理（T12）', () => {
  it('切 Tab2：交付治理列表装载；详情抽屉无下载按钮；读即留痕提示常驻', async () => {
    const wrapper = await mountPage()
    const tabs = wrapper.findAll('.el-tabs__item')
    await tabs[1]?.trigger('click')
    await flushPromises()
    expect(mockedDeliveries).toHaveBeenCalledWith({ pageNum: 1, pageSize: 10 })
    expect(document.body.textContent).toContain(GOVERNANCE_READ_TRACE_HINT)
    expect(document.body.textContent).toContain('DL000009')
    mockedDeliveryGet.mockResolvedValue(delivery())
    const detailBtn = wrapper
      .findAll('button')
      .filter((b) => b.text().includes('详情'))
      .find((b) => b.text().includes('治理') === false)
    await detailBtn?.trigger('click')
    await flushPromises()
    expect(mockedDeliveryGet).toHaveBeenCalledWith('DL000009')
    expect(button(wrapper, '下载加密包')).toBeFalsy()
  })
})

describe('动作留痕区（T13 / G7b）', () => {
  it('空态：无留痕行时空态展示不报错', async () => {
    const wrapper = await mountPage()
    mockedGet.mockResolvedValue(task())
    mockedResults.mockResolvedValue([])
    mockedReviews.mockResolvedValue(page([]))
    mockedActionLogs.mockResolvedValue(page([]))
    await button(wrapper, '治理详情')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('暂无')
  })
})
