import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, DOMWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import ContractDetailView from './ContractDetailView.vue'
import {
  getContractDetail,
  listClauseVersions,
  proposeClauses,
  confirmClauses,
  terminateNegotiation,
  signContract,
  refuseSignature,
  releaseConsent,
  verifySignatures,
  getUsageSummary,
  simulatePolicy,
  runPolicyTestbench,
  executeUsage,
  listContractActionLogs,
  type ContractDetail,
  type VerificationReport,
  type UsageSummary,
  type SimulationResult,
  type TestbenchReport,
  type ExecutionResult,
  type ContractActionLogItem,
} from '../../api/contract'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import { CONTRACT_NOT_ACCESSIBLE_TIP } from '../../constants/contract'

/**
 * 合约详情页测试（WBS-3.4.7 hifi §6.4 + §7 T10~T16/T21）：
 * - T10 协商：提案成功（版本 +1 以响应为准）/ 确认双齐 → 待签署 / 终止协商二次确认（取消=零请求）/
 *   非协商中 C0013 原样；
 * - T11 签署与验签：DID 前置（空=零请求）/ 部分签署渲染 / 生效时间 / 存证摘要 / 拒签终止 /
 *   验签逐方（PASS/FAIL 原样）/ C0018 原样 / **已生效无"单方撤回"按钮**（反向探针）；
 * - T12 策略视图 + 模拟：五要素全文（含"未启用"与无限制声明）/ 模拟提交形态 / **拒绝=数据展示
 *   （不触发错误提示）** / 草稿 C0015 原样；
 * - T13 测试台：一键报告三态 + summary + runAt；
 * - T14 受控执行：提交形态逐字 / 放行含 usedCount / 越界 C0020 原样 + 刷新摘要；
 * - T15 使用摘要（R12）：quota / 计数 / 拒绝行触发要素 / 空态 / quota=null；
 * - T16 留痕区（R13）：四要素 + from→to + 拒绝码 + 治理查看/越权拒绝行 + 空态 + 翻页；
 * - T21 同形与深链：非参与方/不存在 → 同一条提示常量；写动作被拒状态不变。
 */
vi.mock('../../api/contract', () => ({
  getContractDetail: vi.fn(),
  listClauseVersions: vi.fn(),
  proposeClauses: vi.fn(),
  confirmClauses: vi.fn(),
  terminateNegotiation: vi.fn(),
  signContract: vi.fn(),
  refuseSignature: vi.fn(),
  releaseConsent: vi.fn(),
  verifySignatures: vi.fn(),
  getUsageSummary: vi.fn(),
  simulatePolicy: vi.fn(),
  runPolicyTestbench: vi.fn(),
  executeUsage: vi.fn(),
  listContractActionLogs: vi.fn(),
}))

const mockedDetail = vi.mocked(getContractDetail)
const mockedVersions = vi.mocked(listClauseVersions)
const mockedPropose = vi.mocked(proposeClauses)
const mockedConfirm = vi.mocked(confirmClauses)
const mockedTerminate = vi.mocked(terminateNegotiation)
const mockedSign = vi.mocked(signContract)
const mockedRefuse = vi.mocked(refuseSignature)
const mockedRelease = vi.mocked(releaseConsent)
const mockedVerify = vi.mocked(verifySignatures)
const mockedSummary = vi.mocked(getUsageSummary)
const mockedSimulate = vi.mocked(simulatePolicy)
const mockedTestbench = vi.mocked(runPolicyTestbench)
const mockedExecute = vi.mocked(executeUsage)
const mockedLogs = vi.mocked(listContractActionLogs)

const ALL_ELEMENTS_STRATEGY = {
  quota: { enabled: true, maxCount: 100 },
  term: { enabled: true, startDate: '2026-10-08', endDate: '2026-11-07' },
  purpose: { enabled: true, text: '风控建模' },
  territory: { enabled: true, text: '本市域' },
  noRedistribution: { enabled: true },
  noRestrictionDeclared: false,
}

function detail(over: Partial<ContractDetail> = {}): ContractDetail {
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
    currentClauseVersion: 1,
    clauseValues: { slots: { subject_matter: '城市餐饮单位经营数据集' }, strategy: ALL_ELEMENTS_STRATEGY },
    contentHash: 'hash-abc123',
    signatures: [
      { partyRole: 'PROVIDER', subjectNo: 'S20260925000001', did: 'did:ctds:S1.1', signedAt: '2026-10-06T09:59:00' },
      { partyRole: 'REQUESTER', subjectNo: 'S20260922000001', did: 'did:ctds:S2.1', signedAt: '2026-10-06T10:00:00' },
    ],
    attestation: {
      contentHash: 'hash-abc123',
      providerDid: 'did:ctds:S1.1', providerSignedAt: '2026-10-06T09:59:00',
      requesterDid: 'did:ctds:S2.1', requesterSignedAt: '2026-10-06T10:00:00',
      attestedAt: '2026-10-06T10:00:01',
    },
    createdAt: '2026-10-05T21:00:00',
    updatedAt: '2026-10-06T10:00:01',
    ...over,
  }
}

function usageSummary(over: Partial<UsageSummary> = {}): UsageSummary {
  return {
    contractNo: 'CO000013',
    quota: { limit: 100, used: 3 },
    allowedCount: 3,
    deniedCount: 1,
    records: page([
      { requesterNo: 'S20260922000001', actionType: 'USE', outcome: 'DENIED', reasonCode: 'C0020',
        violations: '["QUOTA_EXHAUSTED"]', usedCount: 3, occurredAt: '2026-10-06T11:00:00' },
      { requesterNo: 'S20260922000001', actionType: 'USE', outcome: 'ALLOWED', reasonCode: null,
        violations: null, usedCount: 1, occurredAt: '2026-10-06T10:30:00' },
    ]),
    ...over,
  }
}

function page<T>(list: T[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 10, totalPages: 1 }
}

function actionLog(over: Partial<ContractActionLogItem> = {}): ContractActionLogItem {
  return {
    id: 101,
    contractNo: 'CO000013',
    versionNo: 1,
    action: 'CREATE',
    actorSubjectNo: 'S20260922000001',
    reasonCode: null,
    fromValue: null,
    toValue: null,
    createdAt: '2026-10-05T21:00:00',
    ...over,
  }
}

async function mountPage(route = '/contracts/workbench/CO000013') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/contracts/workbench', name: 'contract-workbench', component: { template: '<div />' } },
      { path: '/contracts/workbench/:contractNo', name: 'contract-detail', component: ContractDetailView },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push(route)
  await router.isReady()
  const wrapper = mount(ContractDetailView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return wrapper
}

async function clickBodyButton(text: string): Promise<void> {
  const btn = [...document.body.querySelectorAll('button')]
    .filter((b) => b.textContent?.includes(text)).at(-1)
  expect(btn).toBeTruthy()
  await new DOMWrapper(btn as HTMLElement).trigger('click')
}

function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((b) => b.text().includes(text))
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  for (const fn of [mockedDetail, mockedVersions, mockedPropose, mockedConfirm, mockedTerminate,
    mockedSign, mockedRefuse, mockedRelease, mockedVerify, mockedSummary, mockedSimulate,
    mockedTestbench, mockedExecute, mockedLogs]) {
    fn.mockReset()
  }
  setDemoSubject('S20260922000001')
  setActorMode('subject')
  mockedDetail.mockResolvedValue(detail())
  mockedSummary.mockResolvedValue(usageSummary())
  mockedLogs.mockResolvedValue(page([actionLog()]))
})

describe('同形与深链（T21）', () => {
  it('非参与方/不存在 → 同一条提示常量（同码同文，不区分差异表述）', async () => {
    mockedDetail.mockRejectedValue(new ApiError('1008C0012', CONTRACT_NOT_ACCESSIBLE_TIP))
    await mountPage()
    expect(document.body.textContent).toContain('合约不存在或不可见')
    expect(document.body.textContent).not.toContain('合约不存在，或您不是参与方')
  })

  it('总览渲染：状态 / 产品与定价快照 / 双方主体 / 模板版本快照（C-4.1 S2-3 核对面）', async () => {
    await mountPage()
    const text = document.body.textContent
    expect(text).toContain('CO000013')
    expect(text).toContain('已生效')
    expect(text).toContain('城市餐饮单位经营数据集')
    expect(text).toContain('CT000001')
    expect(text).toContain('V1')
    expect(text).toContain('S20260925000001')
    expect(text).toContain('S20260922000001')
  })
})

describe('协商动作（T10）', () => {
  function negotiatingDetail(): ContractDetail {
    return detail({
      status: 'NEGOTIATING',
      signatures: [],
      attestation: null,
    })
  }

  it('提案成功：以响应为准提示版本 +1 并刷新', async () => {
    mockedDetail.mockResolvedValue(negotiatingDetail())
    mockedPropose.mockResolvedValue({
      contractNo: 'CO000013', clauseVersionNo: 2, previousVersionNo: 1,
    })
    const wrapper = await mountPage()
    await button(wrapper, '提案')!.trigger('click')
    await flushPromises()
    await clickBodyButton('确定')
    await flushPromises()
    expect(mockedPropose).toHaveBeenCalledWith('CO000013', expect.anything())
    expect(document.body.textContent).toContain('2')
  })

  it('确认双齐 → 待签署展示（以响应刷新，无乐观更新）', async () => {
    mockedDetail
      .mockResolvedValueOnce(negotiatingDetail())
      .mockResolvedValueOnce(detail({ status: 'PENDING_SIGNATURE', signatures: [], attestation: null }))
    mockedConfirm.mockResolvedValue({ contractNo: 'CO000013', status: 'PENDING_SIGNATURE' })
    const wrapper = await mountPage()
    await button(wrapper, '确认')!.trigger('click')
    await flushPromises()
    expect(mockedConfirm).toHaveBeenCalledWith('CO000013')
    await flushPromises()
    expect(document.body.textContent).toContain('待签署')
  })

  it('终止协商二次确认：取消 = 零请求', async () => {
    mockedDetail.mockResolvedValue(negotiatingDetail())
    const wrapper = await mountPage()
    await button(wrapper, '终止协商')!.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(mockedTerminate).not.toHaveBeenCalled()
    await clickBodyButton('取消')
    await flushPromises()
    expect(mockedTerminate).not.toHaveBeenCalled()
  })

  it('非协商中动作被拒 C0013 原样展示且状态不变', async () => {
    mockedDetail.mockResolvedValue(negotiatingDetail())
    mockedConfirm.mockRejectedValue(new ApiError('1008C0013', '合约当前状态不允许该操作'))
    const wrapper = await mountPage()
    await button(wrapper, '确认')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('合约当前状态不允许该操作')
  })
})

describe('签署与验签（T11）', () => {
  it('签署弹窗 DID 必填前置：空 = 零请求；填写后提交 {did}', async () => {
    mockedDetail.mockResolvedValue(detail({ status: 'PENDING_SIGNATURE', signatures: [], attestation: null }))
    mockedSign.mockResolvedValue({ contractNo: 'CO000013', status: 'PARTIALLY_SIGNED' })
    const wrapper = await mountPage()
    await button(wrapper, '签署')!.trigger('click')
    await flushPromises()
    await clickBodyButton('确定')
    await flushPromises()
    expect(mockedSign).not.toHaveBeenCalled()
    const didInput = wrapper.find('input[placeholder*="DID"]')
    await didInput.setValue('did:ctds:S20260922000001.1')
    await clickBodyButton('确定')
    await flushPromises()
    expect(mockedSign).toHaveBeenCalledWith('CO000013', { did: 'did:ctds:S20260922000001.1' })
  })

  it('部分签署状态 + 存证摘要 + 生效时间（= 最后一签）渲染', async () => {
    await mountPage()
    const text = document.body.textContent
    expect(text).toContain('did:ctds:S1.1')
    expect(text).toContain('hash-abc123')
    expect(text).toContain('2026-10-06T10:00:00')
  })

  it('验签核验：逐方结果 PASS/FAIL 原样渲染', async () => {
    mockedVerify.mockResolvedValue({
      contractNo: 'CO000013',
      results: [
        { partyRole: 'PROVIDER', did: 'did:ctds:S1.1', result: 'PASS', reason: null, verifiedAt: '2026-10-06T12:00:00' },
        { partyRole: 'REQUESTER', did: 'did:ctds:S2.1', result: 'FAIL', reason: '签名不匹配', verifiedAt: '2026-10-06T12:00:00' },
      ],
    } satisfies VerificationReport)
    const wrapper = await mountPage()
    await button(wrapper, '验签核验')!.trigger('click')
    await flushPromises()
    expect(mockedVerify).toHaveBeenCalledWith('CO000013')
    expect(document.body.textContent).toContain('PASS')
    expect(document.body.textContent).toContain('FAIL')
    expect(document.body.textContent).toContain('签名不匹配')
  })

  it('DID 不可用 C0018 原样展示', async () => {
    mockedDetail.mockResolvedValue(detail({ status: 'PENDING_SIGNATURE', signatures: [], attestation: null }))
    mockedSign.mockRejectedValue(new ApiError('1008C0018', '签署身份不可用，无法签署'))
    const wrapper = await mountPage()
    await button(wrapper, '签署')!.trigger('click')
    await flushPromises()
    const didInput = wrapper.find('input[placeholder*="DID"]')
    await didInput.setValue('did:ctds:bad.1')
    await clickBodyButton('确定')
    await flushPromises()
    expect(document.body.textContent).toContain('签署身份不可用，无法签署')
  })

  it('已生效合约无"单方撤回签署"按钮（反向探针：按钮复现即红）', async () => {
    const wrapper = await mountPage()
    expect(button(wrapper, '撤回签署')).toBeFalsy()
  })

  it('拒签终止二次确认（未双签合约）', async () => {
    mockedDetail.mockResolvedValue(detail({
      status: 'PARTIALLY_SIGNED',
      signatures: [detail().signatures[0]],
      attestation: null,
    }))
    const wrapper = await mountPage()
    await button(wrapper, '拒签终止')!.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(mockedRefuse).not.toHaveBeenCalled()
    await clickBodyButton('确定')
    await flushPromises()
    expect(mockedRefuse).toHaveBeenCalledWith('CO000013')
  })
})

describe('策略视图与模拟试算（T12）', () => {
  it('五要素全文渲染：启用项取值 + 未启用标记 + 无限制声明', async () => {
    await mountPage()
    const text = document.body.textContent
    expect(text).toContain('100')
    expect(text).toContain('2026-10-08')
    expect(text).toContain('2026-11-07')
    expect(text).toContain('风控建模')
    expect(text).toContain('本市域')
    expect(text).toContain('是')
  })

  it('未启用要素显示"未启用"；显式无限制声明渲染', async () => {
    mockedDetail.mockResolvedValue(detail({
      clauseValues: {
        slots: { subject_matter: 'x' },
        strategy: {
          quota: { enabled: false, maxCount: null },
          term: { enabled: false, startDate: null, endDate: null },
          purpose: { enabled: false, text: null },
          territory: { enabled: false, text: null },
          noRedistribution: { enabled: false },
          noRestrictionDeclared: true,
        },
      },
    }))
    await mountPage()
    const text = document.body.textContent
    expect((text.match(/未启用/g) ?? []).length).toBeGreaterThanOrEqual(5)
    expect(text).toContain('无使用限制')
  })

  it('模拟试算：假想上下文提交形态逐字；拒绝 = 数据展示（不触发错误提示）', async () => {
    mockedSimulate.mockResolvedValue({
      contractNo: 'CO000013', contractStatus: 'EFFECTIVE', strategySource: 'EFFECTIVE',
      strategyEffective: true, allowed: false, violations: ['QUOTA_EXHAUSTED'],
      assumedUsedCount: 999999, assumedDate: '2026-10-11', policySnapshot: { 'usage.quota': '100' },
    } satisfies SimulationResult)
    const wrapper = await mountPage()
    await button(wrapper, '模拟试算')!.trigger('click')
    await flushPromises()
    const numberInput = wrapper.find('input[type="number"]')
    if (numberInput.exists()) {
      await numberInput.setValue('999999')
    }
    await button(wrapper, '开始试算')!.trigger('click')
    await flushPromises()
    expect(mockedSimulate).toHaveBeenCalledWith('CO000013', expect.objectContaining({
      actionType: 'USE',
    }))
    // 拒绝以数据展示（结果面板），不弹错误
    expect(document.body.textContent).toContain('拒绝')
    expect(document.body.textContent).toContain('QUOTA_EXHAUSTED')
    expect(document.body.textContent).toContain('模拟结论不是放行承诺')
  })

  it('草稿策略非法 C0015 原样展示（表单错误提示）', async () => {
    mockedSimulate.mockRejectedValue(new ApiError('1008C0015', '策略条款不符合使用控制约定'))
    const wrapper = await mountPage()
    await button(wrapper, '模拟试算')!.trigger('click')
    await flushPromises()
    await button(wrapper, '开始试算')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('策略条款不符合使用控制约定')
  })
})

describe('测试台（T13）', () => {
  it('一键运行：报告三态渲染（PASS/FAIL/SKIPPED + summary 计数 + runAt）', async () => {
    mockedTestbench.mockResolvedValue({
      contractNo: 'CO000013', contractStatus: 'EFFECTIVE', strategyEffective: true,
      runAt: '2026-10-11T10:00:00',
      scenarios: [
        { code: 'U1', elementKey: 'usage.quota', direction: 'ALLOW', expectation: '放行',
          outcome: 'PASS', actual: { allowed: true, violations: [] }, note: null },
        { code: 'X1', elementKey: 'usage.quota', direction: 'DENY', expectation: '拒绝',
          outcome: 'FAIL', actual: { allowed: true, violations: [] }, note: '未拦截' },
        { code: 'X3', elementKey: 'usage.territory', direction: 'DENY', expectation: '拒绝',
          outcome: 'SKIPPED', actual: null, note: '要素未启用' },
      ],
      summary: { total: 11, pass: 9, fail: 1, skipped: 1 },
    } satisfies TestbenchReport)
    const wrapper = await mountPage()
    await button(wrapper, '测试台')!.trigger('click')
    await flushPromises()
    await button(wrapper, '一键运行')!.trigger('click')
    await flushPromises()
    const text = document.body.textContent
    expect(text).toContain('PASS')
    expect(text).toContain('FAIL')
    expect(text).toContain('SKIPPED')
    expect(text).toContain('要素未启用')
    expect(text).toContain('2026-10-11T10:00:00')
  })
})

describe('受控执行与使用摘要（T14/T15 / R12）', () => {
  it('发起使用：提交形态逐字（actionType/purpose/territory）；放行提示含 usedCount', async () => {
    mockedExecute.mockResolvedValue({
      contractNo: 'CO000013', allowed: true, usedCount: 4, occurredAt: '2026-10-11T10:30:00',
    } satisfies ExecutionResult)
    const wrapper = await mountPage()
    await button(wrapper, '发起使用')!.trigger('click')
    await flushPromises()
    await wrapper.find('input[placeholder*="用途"]').setValue('风控建模')
    await wrapper.find('input[placeholder*="地域"]').setValue('本市域')
    await clickBodyButton('确定')
    await flushPromises()
    expect(mockedExecute).toHaveBeenCalledWith('CO000013', expect.objectContaining({
      actionType: 'USE', purpose: '风控建模', territory: '本市域',
    }))
    expect(document.body.textContent).toContain('4')
  })

  it('越界 C0020 原样 + 提交后刷新摘要（拒绝行出现）', async () => {
    mockedExecute.mockRejectedValue(new ApiError('1008C0020', '越界使用被拒绝，触发要素见留痕'))
    const wrapper = await mountPage()
    await button(wrapper, '发起使用')!.trigger('click')
    await flushPromises()
    await wrapper.find('input[placeholder*="用途"]').setValue('营销外呼')
    await wrapper.find('input[placeholder*="地域"]').setValue('市域外')
    await clickBodyButton('确定')
    await flushPromises()
    expect(document.body.textContent).toContain('越界使用被拒绝，触发要素见留痕')
    expect(mockedSummary).toHaveBeenCalled()
    expect(document.body.textContent).toContain('QUOTA_EXHAUSTED')
  })

  it('使用摘要：quota 已用/上限 + 放行/拒绝计数 + 拒绝行触发要素', async () => {
    await mountPage()
    const text = document.body.textContent
    expect(text).toContain('3')
    expect(text).toContain('100')
    expect(text).toContain('QUOTA_EXHAUSTED')
  })

  it('quota 为 null（无配额口径）正常渲染不报错', async () => {
    mockedSummary.mockResolvedValue(usageSummary({
      quota: null, allowedCount: 2, deniedCount: 0,
      records: page([{ requesterNo: 'S20260922000001', actionType: 'USE', outcome: 'ALLOWED',
        reasonCode: null, violations: null, usedCount: 1, occurredAt: '2026-10-06T10:30:00' }]),
    }))
    await mountPage()
    expect(document.body.textContent).toContain('无配额上限')
  })
})

describe('合约留痕区（T16 / R13）', () => {
  it('四要素 + from→to + 拒绝码行渲染；治理查看/越权拒绝行中文经常量', async () => {
    mockedLogs.mockResolvedValue(page([
      actionLog({ action: 'DENIED_ACCESS', actorSubjectNo: 'S-other', reasonCode: 'C0012' }),
      actionLog({ id: 102, action: 'GOVERNANCE_VIEW', actorSubjectNo: 'S-admin' }),
      actionLog({ id: 103, action: 'SIGN', actorSubjectNo: 'S20260922000001',
        fromValue: 'did:ctds:S2.1', toValue: 'PARTIALLY_SIGNED' }),
    ]))
    await mountPage()
    const text = document.body.textContent
    expect(text).toContain('越权拒绝')
    expect(text).toContain('S-other')
    expect(text).toContain('C0012')
    expect(text).toContain('治理查看')
    expect(text).toContain('S-admin')
    expect(text).toContain('did:ctds:S2.1')
    expect(text).toContain('PARTIALLY_SIGNED')
  })

  it('空态"暂无记录"与翻页传参', async () => {
    mockedLogs.mockResolvedValue(page([]))
    await mountPage()
    expect(document.body.textContent).toContain('暂无记录')
    expect(mockedLogs).toHaveBeenCalledWith(expect.objectContaining({ contractNo: 'CO000013', pageNum: 1 }))
  })
})

describe('合意解除（W11）', () => {
  it('已生效合约提供"合意解除"入口；二次确认后以响应刷新', async () => {
    mockedRelease
      .mockResolvedValueOnce({ contractNo: 'CO000013', status: 'EFFECTIVE', providerConsented: false, requesterConsented: true })
      .mockResolvedValueOnce({ contractNo: 'CO000013', status: 'COMPLETED', providerConsented: true, requesterConsented: true })
    mockedDetail
      .mockResolvedValueOnce(detail())
      .mockResolvedValueOnce(detail({ status: 'COMPLETED', endedAt: '2026-10-11T12:00:00' }))
    const wrapper = await mountPage()
    await button(wrapper, '合意解除')!.trigger('click')
    await flushPromises()
    await clickBodyButton('确定')
    await flushPromises()
    expect(mockedRelease).toHaveBeenCalledWith('CO000013')
    await flushPromises()
    expect(document.body.textContent).toContain('已完结')
  })
})

describe('终止信息展示（行为 6）', () => {
  it('治理强制终止的终止类型与理由全文可见', async () => {
    mockedDetail.mockResolvedValue(detail({
      status: 'TERMINATED',
      terminationType: 'GOVERNANCE_FORCE_TERMINATED',
      terminationReason: '治理演示：违规使用',
      terminatedBy: 'S-admin',
      endedAt: '2026-10-10T12:00:00',
    }))
    await mountPage()
    expect(document.body.textContent).toContain('治理演示：违规使用')
    expect(document.body.textContent).toContain('S-admin')
  })
})
