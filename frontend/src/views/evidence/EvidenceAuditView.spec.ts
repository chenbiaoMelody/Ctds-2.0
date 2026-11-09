import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import EvidenceAuditView from './EvidenceAuditView.vue'
import {
  auditAnomalies,
  auditOverview,
  auditTrails,
  downloadAuditReport,
  generateReport,
  getRecordDetail,
  listRecords,
  type AnomalyItemView,
  type AuditOverview,
  type AuditReportView,
  type EvidenceRecordView,
  type TrailView,
} from '../../api/evidence'
import { setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import { EVIDENCE_DIGEST_HINT } from '../../constants/evidence'

/**
 * 监管审计页测试（WBS-3.8.5 hifi §4 / §8 T2/T3/T5/T6/T7/T8/T9/T10/T16）：
 * - 五 tab 懒加载：无条件型（总览 / 异常项）首次激活自动拉；条件型（存证记录 / 时间线 / 报告）
 *   首次激活渲染表单、由用户查询驱动（records / trails / reports 均 ≥1 条件由后端裁决，Q6-A——
 *   零条件点击照发请求、后端错误原文展示，前端不复制业务规则）；
 * - 界面只读：全页零写按钮（重试上链无入口，N9 / B5-3）；
 * - 错误原文：ApiError 不转译（T8）；E5 失败形态 = 前置检查 P8 实证 1000C0003"记录不存在"；
 * - 脱敏观察点（T16）：审计面出参原样渲染（摘要即摘要），零二次加工。
 */
vi.mock('../../api/evidence', () => ({
  listRecords: vi.fn(),
  getRecordDetail: vi.fn(),
  verifyRecord: vi.fn(),
  auditOverview: vi.fn(),
  auditAnomalies: vi.fn(),
  auditTrails: vi.fn(),
  generateReport: vi.fn(),
  downloadAuditReport: vi.fn(),
}))

const mockedRecords = vi.mocked(listRecords)
const mockedDetail = vi.mocked(getRecordDetail)
const mockedOverview = vi.mocked(auditOverview)
const mockedAnomalies = vi.mocked(auditAnomalies)
const mockedTrails = vi.mocked(auditTrails)
const mockedGenerate = vi.mocked(generateReport)
const mockedDownload = vi.mocked(downloadAuditReport)

function record(over: Partial<EvidenceRecordView> = {}): EvidenceRecordView {
  return {
    evidenceNo: 'EV20261107000004',
    stageType: 'DELIVERY',
    businessNo: 'DL000031',
    operationType: 'DELIVERY_CREATED',
    subjectDidDigest: '0e23678330996e0a0933c1ac7c9af5ad160251db1a18b33ec2e4b85fbd9fc46c',
    occurredAt: '2026-11-07T15:02:33.000Z',
    contentDigest: '58a3eb769af998155b7aa1ca2bbb137a7de9948bacb472a88c9a785fefd0a69b',
    status: 'ON_CHAIN',
    chainTxId: 'LTX000000000004',
    chainBlockNo: 4,
    retryCount: 0,
    lastErrorSummary: null,
    createdAt: '2026-11-07T15:02:33.419Z',
    updatedAt: '2026-11-07T15:02:33.468Z',
    ...over,
  }
}

function anomaly(over: Partial<AnomalyItemView> = {}): AnomalyItemView {
  return {
    ...record({ evidenceNo: 'EV20261107000006', stageType: 'USAGE', businessNo: 'USG000000000569', status: 'FAILED' }),
    anomalyType: 'FAILED',
    verifyConclusion: null,
    verifiedBy: null,
    verifiedAt: null,
    ...over,
  }
}

function overview(over: Partial<AuditOverview> = {}): AuditOverview {
  return {
    matrix: {
      AUTHORIZATION: { PENDING: 0, ON_CHAIN: 1, FAILED: 0 },
      CONTRACT: { PENDING: 0, ON_CHAIN: 1, FAILED: 0 },
      DELIVERY: { PENDING: 0, ON_CHAIN: 1, FAILED: 0 },
      USAGE: { PENDING: 0, ON_CHAIN: 2, FAILED: 1 },
    },
    anomalies: { pendingCount: 0, failedCount: 1, mismatchCount: 0 },
    snapshotAt: '2026-11-08T02:59:19.419Z',
    ...over,
  }
}

function trailView(over: Partial<TrailView> = {}): TrailView {
  return ({
    mode: 'TRAIL',
    evidenceRecord: record(),
    evidenceActions: [
      { action: 'CREATED', fromStatus: null, toStatus: 'PENDING', actorDigest: '9fd0e6660b8e3e52a8c29f6813bf6370e1c070502b56ef04e5b261ccf70e38f9', reasonCode: 'NONE', detail: null, occurredAt: '2026-11-07T15:02:33.419Z' },
      { action: 'CHAIN_SUCCEEDED', fromStatus: 'PENDING', toStatus: 'ON_CHAIN', actorDigest: '9fd0e6660b8e3e52a8c29f6813bf6370e1c070502b56ef04e5b261ccf70e38f9', reasonCode: 'NONE', detail: null, occurredAt: '2026-11-07T15:02:33.468Z' },
    ],
    businessTrail: [
      { source: 'DELIVERY', occurredAt: '2026-11-07T23:02:33Z', actorDigest: null, action: 'DELIVERY', result: 'SUCCESS', summary: '交付 FILE，结果 SUCCESS', sourceStatus: 'OK' },
      { source: 'DELIVERY', occurredAt: '2026-11-07T23:02:35Z', actorDigest: null, action: 'delivery.create', result: 'SUCCESS', summary: 'delivery.create，结果 SUCCESS', sourceStatus: 'UNAVAILABLE' },
    ],
    timelineAvailable: true,
    ...over,
  }) as TrailView
}

function report(over: Partial<AuditReportView> = {}): AuditReportView {
  return {
    reportNo: 'EV-RPT-20261108-0002',
    scope: { stageType: 'DELIVERY' },
    recordCount: 1,
    conclusionSummary: { PASS: 1, MISMATCH: 0, NOT_ON_CHAIN: 0, NOT_VERIFIED: 0 },
    anomalyCount: 0,
    contentDigest: 'ca85f30d54d0d3b55a90ccd6f0d1767bcbb7c957d54abeec63c57ec5e4455675',
    remark: 'probe',
    generatedBy: 'S-admin-evidence',
    generatedAt: '2026-11-08T03:02:09.080Z',
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
      { path: '/evidence', name: 'evidence', component: EvidenceAuditView },
      { path: '/dashboard', name: 'dashboard', component: { template: '<div />' } },
    ],
  })
  await router.push('/evidence')
  await router.isReady()
  const wrapper = mount(EvidenceAuditView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return wrapper
}

async function openTab(wrapper: ReturnType<typeof mount>, label: string) {
  const tab = wrapper.findAll('.el-tabs__item').find((item) => item.text().includes(label))
  expect(tab, `页签「${label}」应存在`).toBeTruthy()
  await tab!.trigger('click')
  await flushPromises()
}

/** 下拉选择：点开 .el-select 类名容器 → 按 option 文本点击（沿 BillingManageView.spec 套路）。 */
async function pickSelect(wrapper: ReturnType<typeof mount>, cls: string, optionText: string) {
  await wrapper.find(cls).trigger('click')
  await flushPromises()
  const option = [...document.querySelectorAll('.el-select-dropdown__item')].find((el) =>
    el.textContent?.includes(optionText),
  )
  expect(option, `下拉项「${optionText}」应存在`).toBeTruthy()
  ;(option as HTMLElement).click()
  await flushPromises()
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.clearAllMocks()
  vi.spyOn(ElMessage, 'error')
  setDemoSubject('S-admin-evidence')
  setActorMode('operator')
  mockedOverview.mockResolvedValue(overview())
  mockedAnomalies.mockResolvedValue(page([anomaly()]))
  mockedRecords.mockResolvedValue(page([record()]))
  mockedTrails.mockResolvedValue(trailView())
  mockedDetail.mockResolvedValue({ record: record(), actions: [] })
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

describe('T2 审计总览 tab（C-8.2 S1-1 基线）', () => {
  it('挂载即拉 E1：四环节×三状态矩阵 + 三计数卡 + 快照时点原样', async () => {
    const wrapper = await mountView()
    expect(mockedOverview).toHaveBeenCalledWith(undefined, undefined)
    const text = wrapper.text()
    expect(text).toContain('授权')
    expect(text).toContain('合约')
    expect(text).toContain('交付')
    expect(text).toContain('使用')
    expect(text).toContain('已上链')
    expect(text).toContain('上链失败')
    expect(text).toContain('2')
    expect(text).toContain('2026-11-08T02:59:19.419Z')
  })

  it('FAILED > 0 单元格以 danger 徽标呈现 + 计数卡数值结构断言（S1-1 失败可见）', async () => {
    const wrapper = await mountView()
    const dangerTags = wrapper.findAll('.el-tag--danger')
    expect(dangerTags.length).toBeGreaterThanOrEqual(1)
    expect(dangerTags.some((t) => t.text() === '1')).toBe(true)
    expect(wrapper.find('.anomaly-card-failed .anomaly-card-num').text()).toBe('1')
    expect(wrapper.find('.anomaly-card-pending .anomaly-card-num').text()).toBe('0')
    expect(wrapper.find('.anomaly-card-mismatch .anomaly-card-num').text()).toBe('0')
  })

  it('异常计数卡点击跳异常项 tab（auditAnomalies 触发）', async () => {
    const wrapper = await mountView()
    const failedCard = wrapper.find('.anomaly-card-failed')
    expect(failedCard.exists()).toBe(true)
    await failedCard.trigger('click')
    await flushPromises()
    expect(mockedAnomalies).toHaveBeenCalled()
  })

  it('加载失败：错误原文 el-alert + 重试重拉（不转译）', async () => {
    mockedOverview.mockRejectedValue(new Error('查询时间窗不得超过 90 天'))
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('查询时间窗不得超过 90 天')
    const retry = wrapper.find('.retry-btn')
    expect(retry.exists()).toBe(true)
    mockedOverview.mockResolvedValue(overview())
    await retry.trigger('click')
    await flushPromises()
    expect(mockedOverview).toHaveBeenCalledTimes(2)
  })
})

describe('T3 存证记录 tab（C-8.2 S1-3）', () => {
  it('条件型 tab：首次激活渲染表单不自动拉数；填单号查询后按参数请求', async () => {
    const wrapper = await mountView()
    await openTab(wrapper, '存证记录')
    expect(mockedRecords).not.toHaveBeenCalled()

    await wrapper.find('.records-business-no input').setValue('DL000031')
    await wrapper.find('.records-search-btn').trigger('click')
    await flushPromises()
    expect(mockedRecords).toHaveBeenCalledTimes(1)
    expect(mockedRecords).toHaveBeenCalledWith(
      expect.objectContaining({ pageNum: 1, pageSize: 10, businessNo: 'DL000031' }),
    )
    expect(wrapper.text()).toContain('EV20261107000004')
  })

  it('环节下拉过滤：选择"交付"后查询 → stageType=DELIVERY；单号清空则不进查询串（空值剔除）', async () => {
    const wrapper = await mountView()
    await openTab(wrapper, '存证记录')
    await pickSelect(wrapper, '.records-stage', '交付')
    await wrapper.find('.records-search-btn').trigger('click')
    await flushPromises()
    expect(mockedRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNum: 1, pageSize: 10, stageType: 'DELIVERY' }),
    )
    const first = mockedRecords.mock.calls[0][0]
    expect(first.businessNo).toBeUndefined()
  })

  it('零条件查询照发请求（Q6-A 后端唯一裁决）→ 1012C0002 错误原文展示', async () => {
    const wrapper = await mountView()
    await openTab(wrapper, '存证记录')
    mockedRecords.mockRejectedValue(new Error('至少需要一个过滤条件'))
    await wrapper.find('.records-search-btn').trigger('click')
    await flushPromises()
    expect(mockedRecords).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('至少需要一个过滤条件')
  })

  it('行点击打开详情抽屉（getRecordDetail 以行编号调用）', async () => {
    const wrapper = await mountView()
    await openTab(wrapper, '存证记录')
    await wrapper.find('.records-search-btn').trigger('click')
    await flushPromises()
    // 经单元格冒泡触发 el-table row-click（tr 直接触发在 EP 2.14 下不派发）
    const cell = wrapper.find('.records-table .el-table__row td')
    await cell.trigger('click')
    await flushPromises()
    expect(mockedDetail).toHaveBeenCalledWith('EV20261107000004')
  })

  it('分页交互：total > pageSize 渲染分页条，current-change 以 pageNum=2 重查（评审④ F-1）', async () => {
    mockedRecords.mockResolvedValue({ list: [record()], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountView()
    await openTab(wrapper, '存证记录')
    await wrapper.find('.records-search-btn').trigger('click')
    await flushPromises()
    const pagination = wrapper.findComponent({ name: 'ElPagination' })
    expect(pagination.exists()).toBe(true)
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(mockedRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNum: 2, pageSize: 10 }),
    )
  })

  it('空数据查询渲染空态文案（T2/T3 空态观察点，评审④ F-12）', async () => {
    mockedRecords.mockResolvedValue(page([]))
    const wrapper = await mountView()
    await openTab(wrapper, '存证记录')
    await wrapper.find('.records-search-btn').trigger('click')
    await flushPromises()
    expect(wrapper.find('.records-table').text()).toContain('暂无数据')
  })
})

describe('T5 异常项 tab（C-8.2 S1-1）', () => {
  it('首次激活自动拉数（默认窗口 from/to 未设）：三类异常标签齐 + MISMATCH 行附核验结论 / 核验人摘要 / 时点', async () => {
    mockedAnomalies.mockResolvedValue(
      page([
        anomaly(),
        anomaly({
          evidenceNo: 'EV20261107000005',
          anomalyType: 'MISMATCH',
          status: 'ON_CHAIN',
          verifyConclusion: 'MISMATCH',
          verifiedBy: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
          verifiedAt: '2026-11-08T04:00:00.000Z',
        }),
        anomaly({
          evidenceNo: 'EV20261107000003',
          anomalyType: 'PENDING',
          status: 'PENDING',
        }),
      ]),
    )
    const wrapper = await mountView()
    await openTab(wrapper, '异常项')
    expect(mockedAnomalies).toHaveBeenCalledWith(
      expect.objectContaining({ pageNum: 1, pageSize: 10, from: undefined, to: undefined }),
    )
    const text = wrapper.text()
    expect(text).toContain('EV20261107000006')
    expect(text).toContain('上链失败')
    expect(text).toContain('不一致')
    expect(text).toContain('待上链')
    expect(text).toContain('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    expect(text).toContain('2026-11-08T04:00:00.000Z')
  })

  it('时间范围过滤透传：异常项 picker 赋值后查询 → auditAnomalies 携 from/to（评审① F1）', async () => {
    const wrapper = await mountView()
    await openTab(wrapper, '异常项')
    mockedAnomalies.mockClear()
    // 五个 datetimerange 按模板声明序：总览 / 记录 / 异常项 / 时间线 / 报告
    const pickers = wrapper.findAllComponents({ name: 'ElDatePicker' })
    expect(pickers.length, '五处时间范围选择器应齐备').toBe(5)
    pickers[2].vm.$emit('update:modelValue', ['2026-10-01T00:00:00Z', '2026-10-20T00:00:00Z'])
    await flushPromises()
    await wrapper.find('.anomalies-search-btn').trigger('click')
    await flushPromises()
    expect(mockedAnomalies).toHaveBeenCalledWith(
      expect.objectContaining({ pageNum: 1, pageSize: 10, from: '2026-10-01T00:00:00Z', to: '2026-10-20T00:00:00Z' }),
    )
  })
})

describe('T6/T7 时间线与调阅 tab（C-8.2 S1-2 / S1-3 / S1-4 / S1-6）', () => {
  it('TRAIL 单笔：单笔卡 + 双段节点渲染 + UNAVAILABLE 降级标记 + 警示条 + 调阅留痕灰字', async () => {
    const wrapper = await mountView()
    await openTab(wrapper, '时间线与调阅')
    expect(mockedTrails).not.toHaveBeenCalled()

    await wrapper.find('.trail-business-no input').setValue('DL000031')
    await wrapper.find('.trail-search-btn').trigger('click')
    await flushPromises()
    expect(mockedTrails).toHaveBeenCalledWith(expect.objectContaining({ businessNo: 'DL000031' }))

    const text = wrapper.text()
    expect(text).toContain('EV20261107000004')
    expect(text).toContain('DL000031')
    // 双段时间线：存证事件 2 节点 + 业务留痕 2 节点
    expect(wrapper.findAll('.el-timeline-item').length).toBe(4)
    expect(text).toContain('创建')
    expect(text).toContain('交付 FILE，结果 SUCCESS')
    // UNAVAILABLE 节点显式降级（红色标记 + 警示条），不伪装连续
    expect(wrapper.find('.trail-node-unavailable').exists()).toBe(true)
    expect(text).toContain('跨域留痕暂不可达，时间线可能不完整')
    // 调阅留痕灰字提示（S1-6：留痕由后端落库，界面仅提示）
    expect(text).toContain('调阅动作由平台自动留痕')
  })

  it('TRAIL 卡内容摘要与时间线操作人摘要：中截断 + 点击复制（§4 通用契约收口，独立复审 P3-1）', async () => {
    const wrapper = await mountView()
    await openTab(wrapper, '时间线与调阅')
    await wrapper.find('.trail-business-no input').setValue('DL000031')
    await wrapper.find('.trail-search-btn').trigger('click')
    await flushPromises()

    // TRAIL 卡内容摘要：中截断（前 8 + … + 后 8）+ 复制引导（沿报告卡 :749 同款契约）
    const cardDigest = wrapper.find('.trail-record-card .digest-copy')
    expect(cardDigest.exists()).toBe(true)
    expect(cardDigest.text()).toBe('58a3eb76…efd0a69b')
    expect(cardDigest.attributes('title')).toBe(EVIDENCE_DIGEST_HINT)
    // 时间线操作人摘要同契约（evidenceActions 两节点；businessTrail 节点无 actorDigest 不挂复制）
    const nodeDigests = wrapper.findAll('.trail-node .digest-copy')
    expect(nodeDigests.length).toBe(2)
    expect(nodeDigests[0].text()).toBe('9fd0e666…f70e38f9')
  })

  it('LIST 清单：条件查询 → 分页表 + trailHint 缺省兜底展示（mode 判模；评审④ F-4）', async () => {
    mockedTrails.mockResolvedValue(
      trailView({
        mode: 'LIST',
        evidenceRecord: null,
        evidenceActions: null,
        businessTrail: null,
        timelineAvailable: null,
        list: [record()],
        total: 1,
        pageNum: 1,
        pageSize: 10,
        totalPages: 1,
        trailHint: null,
      }),
    )
    const wrapper = await mountView()
    await openTab(wrapper, '时间线与调阅')
    // 条件组用主体摘要输入（64 位小写 hex；环节下拉跨 tab 下拉实例污染不在此用例覆盖）
    await wrapper.find('.trail-subject-digest input').setValue('0e23678330996e0a0933c1ac7c9af5ad160251db1a18b33ec2e4b85fbd9fc46c')
    await wrapper.find('.trail-search-btn').trigger('click')
    await flushPromises()
    expect(mockedTrails).toHaveBeenCalledWith(
      expect.objectContaining({
        pageNum: 1,
        pageSize: 10,
        subjectDigest: '0e23678330996e0a0933c1ac7c9af5ad160251db1a18b33ec2e4b85fbd9fc46c',
      }),
    )
    const text = wrapper.text()
    expect(text).toContain('EV20261107000004')
    expect(text).toContain('输入业务单据号查看全链时间线')
  })
})

describe('T8 错误原文呈现（C-8.2 S1-5 / S1-7；前端零转译）', () => {
  it('零条件时间线查询：后端 1012C0002 原文 el-alert（"至少需要一个过滤条件"全句）', async () => {
    mockedTrails.mockRejectedValue(new Error('至少需要一个过滤条件'))
    const wrapper = await mountView()
    await openTab(wrapper, '时间线与调阅')
    await wrapper.find('.trail-search-btn').trigger('click')
    await flushPromises()
    const alert = wrapper.find('.list-error')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('至少需要一个过滤条件')
  })

  it('治理族 403 + 1000C0005 原文展示（不拦截不转译）', async () => {
    mockedAnomalies.mockRejectedValue(new Error('无权限访问'))
    const wrapper = await mountView()
    await openTab(wrapper, '异常项')
    expect(wrapper.find('.list-error').text()).toContain('无权限访问')
  })

  it('审计族 400 + 1000C0003"记录不存在"同码同文原样展示（不做存在性分支）', async () => {
    mockedTrails.mockRejectedValue(new Error('记录不存在'))
    const wrapper = await mountView()
    await openTab(wrapper, '时间线与调阅')
    await wrapper.find('.trail-business-no input').setValue('DL999999')
    await wrapper.find('.trail-search-btn').trigger('click')
    await flushPromises()
    expect(wrapper.find('.list-error').text()).toContain('记录不存在')
  })
})

describe('T9 审计报告 tab（C-8.2 S2-1 / S2-2）', () => {
  it('生成表单 → E4 请求体 → 结果卡全字段（编号 / 范围 / 条数 / 四键结论分布 / 异常 / 摘要 / 生成要素）', async () => {
    mockedGenerate.mockResolvedValue(report())
    const wrapper = await mountView()
    await openTab(wrapper, '审计报告')
    expect(mockedGenerate).not.toHaveBeenCalled()

    // 条件透传以单号输入验证（环节下拉行为同 T3-2 已覆盖：同 helper 同组件模式）
    await wrapper.find('.report-business-no input').setValue('DL000031')
    await wrapper.find('.report-remark input').setValue('probe')
    await wrapper.find('.report-generate-btn').trigger('click')
    await flushPromises()
    expect(mockedGenerate).toHaveBeenCalledWith({ businessNo: 'DL000031', remark: 'probe' })

    const card = wrapper.find('.report-result-card')
    expect(card.exists()).toBe(true)
    const text = card.text()
    expect(text).toContain('EV-RPT-20261108-0002')
    expect(text).toContain('DELIVERY')
    expect(text).toContain('1')
    // conclusionSummary 四键徽标（P1 实测含 NOT_VERIFIED）
    expect(text).toContain('通过')
    expect(text).toContain('不一致')
    expect(text).toContain('未上链')
    expect(text).toContain('未核验')
    expect(text).toContain('ca85f30d…e4455675')
    expect(text).toContain('S-admin-evidence')
    expect(text).toContain('2026-11-08T03:02:09.080Z')
    expect(text).toContain('probe')
  })

  it('零条件生成照发请求（Q6-A）→ 后端错误原文；≥1 条件仅为引导灰字', async () => {
    mockedGenerate.mockRejectedValue(new Error('至少需要一个过滤条件'))
    const wrapper = await mountView()
    await openTab(wrapper, '审计报告')
    expect(wrapper.text()).toContain('至少填写一个条件')
    await wrapper.find('.report-generate-btn').trigger('click')
    await flushPromises()
    expect(mockedGenerate).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.list-error').text()).toContain('至少需要一个过滤条件')
  })
})

describe('T10 报告下载（C-8.2 S2-4 / S2-7）', () => {
  function stubBlobUrl() {
    const createObjectURL = vi.fn(() => 'blob:mock-url')
    const revokeObjectURL = vi.fn()
    const FakeURL = class extends URL {}
    Object.assign(FakeURL, { createObjectURL, revokeObjectURL })
    vi.stubGlobal('URL', FakeURL)
    return { createObjectURL, revokeObjectURL }
  }

  it('结果卡 CSV / JSON 双格式下载：downloadAuditReport + Blob 落 a[download]', async () => {
    const { createObjectURL, revokeObjectURL } = stubBlobUrl()
    mockedGenerate.mockResolvedValue(report())
    mockedDownload.mockResolvedValue({ blob: new Blob(['csv']), fileName: 'EV-RPT-20261108-0002.csv' })
    const wrapper = await mountView()
    await openTab(wrapper, '审计报告')
    await wrapper.find('.report-generate-btn').trigger('click')
    await flushPromises()

    await wrapper.find('.report-csv-btn').trigger('click')
    await flushPromises()
    expect(mockedDownload).toHaveBeenCalledWith('EV-RPT-20261108-0002', 'csv')
    expect(createObjectURL).toHaveBeenCalled()
    expect(revokeObjectURL).toHaveBeenCalled()

    await wrapper.find('.report-json-btn').trigger('click')
    await flushPromises()
    expect(mockedDownload).toHaveBeenLastCalledWith('EV-RPT-20261108-0002', 'json')
  })

  it('下载失败：E5 实证形态 1000C0003"记录不存在"原文弹出（ElMessage.error）', async () => {
    stubBlobUrl()
    mockedGenerate.mockResolvedValue(report())
    mockedDownload.mockRejectedValue(new Error('记录不存在'))
    const wrapper = await mountView()
    await openTab(wrapper, '审计报告')
    await wrapper.find('.report-generate-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.report-csv-btn').trigger('click')
    await flushPromises()
    expect(ElMessage.error).toHaveBeenCalledWith('记录不存在')
  })

  it('按号补下载区：空编号前置拦截（零请求，评审④ F-3）', async () => {
    stubBlobUrl()
    const wrapper = await mountView()
    await openTab(wrapper, '审计报告')
    await wrapper.find('.report-redownload-csv-btn').trigger('click')
    await flushPromises()
    expect(mockedDownload).not.toHaveBeenCalled()
    expect(ElMessage.error).toHaveBeenCalledWith('请输入报告编号')
  })

  it('按号补下载区：输入历史报告号直接下载（S2-7 界面路）', async () => {
    stubBlobUrl()
    mockedDownload.mockResolvedValue({ blob: new Blob(['csv']), fileName: 'EV-RPT-20261107-0001.csv' })
    const wrapper = await mountView()
    await openTab(wrapper, '审计报告')
    await wrapper.find('.report-redownload-no input').setValue('EV-RPT-20261107-0001')
    await wrapper.find('.report-redownload-csv-btn').trigger('click')
    await flushPromises()
    expect(mockedDownload).toHaveBeenCalledWith('EV-RPT-20261107-0001', 'csv')
  })
})

describe('T16 脱敏观察点（C-8.2 S1-8 / S2-6）', () => {
  it('审计面渲染出口：核验人摘要原样呈现、治理档原文字段零渲染（mock 数据驱动）', async () => {
    mockedAnomalies.mockResolvedValue(
      page([
        anomaly({
          anomalyType: 'MISMATCH',
          status: 'ON_CHAIN',
          verifyConclusion: 'MISMATCH',
          verifiedBy: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
          verifiedAt: '2026-11-08T04:00:00.000Z',
        }),
      ]),
    )
    const wrapper = await mountView()
    await openTab(wrapper, '异常项')
    const text = wrapper.text()
    // 出参摘要原样渲染（零截断改写、零还原）
    expect(text).toContain('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    // 审计面组件不引用治理面原文字段（actorSubjectNo 为治理 detail 出参，mock 中未提供亦不得出现）
    expect(text).not.toContain('S20260922000001')
  })
})
