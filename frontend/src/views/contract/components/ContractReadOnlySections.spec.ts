import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ContractReadOnlySections from './ContractReadOnlySections.vue'
import type {
  ClauseFramework,
  ClauseValues,
  ClauseVersionItem,
  ContractActionLogItem,
  ContractDetail,
} from '../../../api/contract'

/**
 * 合约只读五分区共享组件测试（DB-44 hifi §5.1 t1~t6；组件为纯展示——只经 props 驱动，
 * 不发请求、不 mock api）：
 * - t1 总览两视角字段集差异（variant 开关）；
 * - t2 条款全文标签两腿（框架命中 → "名称（键）"；缺失 → 键名）；
 * - t3 策略五要素逐分支；
 * - t4 版本链：双方确认时间列（Q2-2-A 两视角齐备）+ 展开槽位 + 变更明细 + 空态；
 * - t5 留痕六列 + 空态 + 翻页 emit；
 * - t6 variant 反向探针（同一 fixture 两视角渲染文本必不同——防开关空转）。
 */

const FULL_STRATEGY: ClauseValues['strategy'] = {
  quota: { enabled: true, maxCount: 100 },
  term: { enabled: true, startDate: '2026-10-08', endDate: '2026-11-07' },
  purpose: { enabled: true, text: '风控建模' },
  territory: { enabled: true, text: '本市域' },
  noRedistribution: { enabled: true },
  noRestrictionDeclared: false,
}

function detailFixture(over: Partial<ContractDetail> = {}): ContractDetail {
  return {
    contractNo: 'CO000013',
    productId: 7,
    productName: '城市餐饮单位经营数据集',
    pricingModel: '按次',
    priceAmount: '1.50',
    providerSubjectNo: 'S20260925000001',
    requesterSubjectNo: 'S20260922000001',
    templateNo: 'CT000001',
    templateVersionNo: 1,
    status: 'EFFECTIVE',
    terminationType: null,
    terminationReason: null,
    terminatedBy: null,
    effectiveAt: '2026-10-06T10:00:00',
    endedAt: null,
    currentClauseVersion: 2,
    clauseValues: { slots: { subject_matter: '城市餐饮单位经营数据集' }, strategy: FULL_STRATEGY },
    contentHash: 'hash-abc123',
    signatures: [],
    attestation: null,
    createdAt: '2026-10-05T21:00:00',
    updatedAt: '2026-10-06T10:00:00',
    ...over,
  }
}

const FRAMEWORK: ClauseFramework = {
  slots: [{ key: 'subject_matter', name: '标的', required: true, guide: null }],
}

const VERSIONS: ClauseVersionItem[] = [
  {
    versionNo: 1,
    clauseValues: { slots: { subject_matter: '初始标的' }, strategy: FULL_STRATEGY },
    changes: null,
    proposedBy: null,
    proposedAt: null,
    confirmedProviderAt: null,
    confirmedRequesterAt: null,
  },
  {
    versionNo: 2,
    clauseValues: { slots: { subject_matter: '变更后标的' }, strategy: FULL_STRATEGY },
    changes: [{ slot: 'subject_matter', from: '初始标的', to: '变更后标的' }],
    proposedBy: 'S20260922000001',
    proposedAt: '2026-10-11T09:00:00',
    confirmedProviderAt: '2026-10-11T10:00:00',
    confirmedRequesterAt: '2026-10-11T11:00:00',
  },
]

const LOGS: ContractActionLogItem[] = [
  {
    id: 201,
    contractNo: 'CO000013',
    versionNo: 2,
    action: 'GOVERNANCE_VIEW',
    actorSubjectNo: 'S20260925000001',
    reasonCode: null,
    fromValue: null,
    toValue: null,
    createdAt: '2026-10-11T09:00:00',
  },
  {
    id: 202,
    contractNo: 'CO000013',
    versionNo: null,
    action: 'DENIED_ACCESS',
    actorSubjectNo: 'S20260922000001',
    reasonCode: 'C0012',
    fromValue: 'V1',
    toValue: 'V2',
    createdAt: '2026-10-11T09:30:00',
  },
]

function mountSections(over: Partial<{
  variant: 'detail' | 'governance'
  detail: ContractDetail
  clauseVersions: ClauseVersionItem[]
  framework: ClauseFramework | null
  actionLogs: ContractActionLogItem[]
  logsLoading: boolean
  logsTotal: number
  logsPage: number
  logsPageSize: number
}> = {}) {
  document.body.innerHTML = ''
  return mount(ContractReadOnlySections, {
    attachTo: document.body,
    global: { plugins: [ElementPlus] },
    props: {
      variant: 'detail',
      detail: detailFixture(),
      clauseVersions: VERSIONS,
      framework: null,
      actionLogs: LOGS,
      logsLoading: false,
      logsTotal: LOGS.length,
      logsPage: 1,
      logsPageSize: 20,
      ...over,
    },
  })
}

function bodyText(): string {
  return document.body.textContent ?? ''
}

/** 组件渲染文本（含插值之间的空白折叠，便于做"（键）"形态断言）。 */
function normalizedText(): string {
  return bodyText().replace(/\s+/g, '')
}

describe('总览两视角（t1 / DB-44 hifi §2.3）', () => {
  it('详情视角：3 列 12 项（含创建 / 更新），状态渲染为 el-tag', () => {
    const wrapper = mountSections({ variant: 'detail' })
    const text = bodyText()
    expect(text).toContain('合约总览')
    expect(text).toContain('创建 / 更新')
    expect(text).toContain('2026-10-05T21:00:00 / 2026-10-06T10:00:00')
    expect(text).toContain('生效时间')
    expect(text).toContain('版本快照 V1')
    expect(wrapper.find('.el-descriptions .el-tag').exists()).toBe(true)
  })

  it('治理视角：2 列 10 项（无创建 / 更新），状态为纯文本（无 el-tag）', () => {
    const wrapper = mountSections({ variant: 'governance' })
    const text = bodyText()
    expect(text).toContain('合约总览')
    expect(text).not.toContain('创建 / 更新')
    expect(text).not.toContain('2026-10-05T21:00:00')
    expect(text).toContain('生效 / 完结')
    expect(text).toContain('（V1）')
    expect(wrapper.find('.el-descriptions .el-tag').exists()).toBe(false)
  })
})

describe('条款全文标签两腿（t2 / 槽位名单点）', () => {
  it('框架命中：标签显示"名称（键）"', async () => {
    mountSections({ framework: FRAMEWORK })
    await flushPromises()
    expect(normalizedText()).toContain('标的（subject_matter）')
  })

  it('框架缺失 / 键未命中：标签回退键名（无括号形态）', async () => {
    mountSections({ framework: null })
    await flushPromises()
    expect(normalizedText()).toContain('subject_matter')
    expect(normalizedText()).not.toContain('（subject_matter）')
    mountSections({ framework: { slots: [{ key: 'other_slot', name: '其他', required: false, guide: null }] } })
    await flushPromises()
    expect(normalizedText()).not.toContain('（subject_matter）')
  })
})

describe('策略五要素（t3 / strategyLine 单点）', () => {
  it('启用要素逐项渲染；未启用 → "未启用"；空取值 → "—"', async () => {
    mountSections()
    await flushPromises()
    const text = bodyText()
    expect(text).toContain('次数上限 100')
    expect(text).toContain('2026-10-08 ~ 2026-11-07')
    expect(text).toContain('风控建模')
    expect(text).toContain('本市域')
    expect(text).toContain('是')
    expect(text).toContain('未声明')
  })

  it('未启用与空值分支：未启用要素显示"未启用"，quota 空值显示"次数上限 —"', async () => {
    mountSections({
      detail: detailFixture({
        clauseValues: {
          slots: { subject_matter: 'x' },
          strategy: {
            quota: { enabled: true, maxCount: null },
            term: { enabled: false, startDate: null, endDate: null },
            purpose: { enabled: true, text: null },
            territory: { enabled: false, text: null },
            noRedistribution: { enabled: false },
            noRestrictionDeclared: true,
          },
        },
      }),
    })
    await flushPromises()
    const text = bodyText()
    expect(text).toContain('次数上限 —')
    expect(text).toContain('未启用')
    expect(text).toContain('已启用')
    expect(text).toContain('是（显式声明）')
  })
})

describe('条款版本链（t4 / R8；Q2-2-A 双方确认时间列两视角齐备）', () => {
  it('详情视角：双方确认时间列渲染（含 "—" 空值腿）+ 变更明细 from→to', async () => {
    const wrapper = mountSections({ variant: 'detail' })
    await flushPromises()
    const text = bodyText()
    expect(text).toContain('双方确认时间')
    expect(text).toContain('提供方：2026-10-11T10:00:00')
    expect(text).toContain('需求方：2026-10-11T11:00:00')
    expect(text).toContain('提供方：—')
    expect(text).toContain('subject_matter：初始标的 → 变更后标的')
    expect(wrapper.find('.el-table__expand-icon').exists()).toBe(true)
  })

  it('治理视角：双方确认时间列同样齐备', async () => {
    mountSections({ variant: 'governance' })
    await flushPromises()
    expect(bodyText()).toContain('双方确认时间')
  })

  it('无版本链：空态"（仅初始版本）"', async () => {
    mountSections({ clauseVersions: [] })
    await flushPromises()
    expect(bodyText()).toContain('（仅初始版本）')
  })
})

describe('合约留痕区（t5 / R13）', () => {
  it('六列渲染：动作中文 / 版本号 / 操作者 / 时间 / from→to / 拒绝码', async () => {
    mountSections()
    await flushPromises()
    const text = bodyText()
    expect(text).toContain('治理查看')
    expect(text).toContain('越权拒绝')
    expect(text).toContain('S20260925000001')
    expect(text).toContain('2026-10-11T09:00:00')
    expect(text).toContain('V1 → V2')
    expect(text).toContain('C0012')
  })

  it('空留痕：空态"暂无记录"', async () => {
    mountSections({ actionLogs: [], logsTotal: 0 })
    await flushPromises()
    expect(bodyText()).toContain('暂无记录')
  })

  it('翻页：点击下一页 emit logs-page-change（载荷 = 目标页号）', async () => {
    const wrapper = mountSections({ logsTotal: 25, logsPage: 1, logsPageSize: 20 })
    await flushPromises()
    const next = wrapper.find('.btn-next')
    expect(next.exists()).toBe(true)
    await next.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('logs-page-change')?.[0]).toEqual([2])
  })
})

describe('variant 反向探针（t6——防开关空转）', () => {
  it('同一 fixture 两视角渲染文本必不同', async () => {
    mountSections({ variant: 'detail' })
    await flushPromises()
    const detailText = bodyText()
    mountSections({ variant: 'governance' })
    await flushPromises()
    const governanceText = bodyText()
    expect(detailText).not.toBe(governanceText)
  })
})
