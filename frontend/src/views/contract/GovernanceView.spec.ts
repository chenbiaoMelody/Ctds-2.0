import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, DOMWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import GovernanceView from './GovernanceView.vue'
import {
  listGovernanceContracts,
  getGovernanceDetail,
  forceTerminate,
  listContractActionLogs,
  type GovernanceContract,
  type GovernanceDetail,
  type ContractActionLogItem,
  type ContractDetail,
} from '../../api/contract'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import { CONTRACT_NOT_ACCESSIBLE_TIP } from '../../constants/contract'

/**
 * 合约治理页测试（WBS-3.4.7 hifi §6.5 + §7 T17）：
 * - R10 全量列表：含协商中/已终止 + 终止类型 + 状态过滤 + "查看即留痕"提示；
 * - R11 直查详情：复用分区只读、**无动作条**；治理直查对象缺失同款同形提示；
 * - **治理页无"发起使用"入口**（反向探针：入口复现即红——服务端 t12 口径）；
 * - 强制终止：理由前置（空 = 零请求）/ 成功刷新 / 终止理由全文展示。
 */
vi.mock('../../api/contract', () => ({
  listGovernanceContracts: vi.fn(),
  getGovernanceDetail: vi.fn(),
  forceTerminate: vi.fn(),
  listContractActionLogs: vi.fn(),
}))

const mockedGovernance = vi.mocked(listGovernanceContracts)
const mockedGovernanceDetail = vi.mocked(getGovernanceDetail)
const mockedForce = vi.mocked(forceTerminate)
const mockedLogs = vi.mocked(listContractActionLogs)

function governanceItem(over: Partial<GovernanceContract> = {}): GovernanceContract {
  return {
    contractNo: 'CO000013',
    productName: '城市餐饮单位经营数据集',
    providerSubjectNo: 'S20260925000001',
    requesterSubjectNo: 'S20260922000001',
    status: 'EFFECTIVE',
    terminationType: null,
    currentClauseVersion: 1,
    effectiveAt: '2026-10-06T10:00:00',
    updatedAt: '2026-10-06T10:00:00',
    ...over,
  }
}

function governanceDetail(over: Partial<ContractDetail> = {}): GovernanceDetail {
  return {
    detail: {
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
      clauseValues: {
        slots: { subject_matter: '城市餐饮单位经营数据集' },
        strategy: {
          quota: { enabled: true, maxCount: 100 },
          term: { enabled: false, startDate: null, endDate: null },
          purpose: { enabled: true, text: '风控建模' },
          territory: { enabled: true, text: '本市域' },
          noRedistribution: { enabled: false },
          noRestrictionDeclared: false,
        },
      },
      contentHash: 'hash-abc123',
      signatures: [],
      attestation: null,
      createdAt: '2026-10-05T21:00:00',
      updatedAt: '2026-10-06T10:00:00',
      ...over,
    },
    clauseVersions: [],
  }
}

function actionLog(over: Partial<ContractActionLogItem> = {}): ContractActionLogItem {
  return {
    id: 201,
    contractNo: 'CO000013',
    versionNo: 1,
    action: 'GOVERNANCE_VIEW',
    actorSubjectNo: 'S-admin',
    reasonCode: null,
    fromValue: null,
    toValue: null,
    createdAt: '2026-10-11T09:00:00',
    ...over,
  }
}

function page<T>(list: T[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage(route = '/contracts/governance') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/contracts/governance', name: 'contract-governance', component: GovernanceView },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push(route)
  await router.isReady()
  const wrapper = mount(GovernanceView, {
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
  for (const fn of [mockedGovernance, mockedGovernanceDetail, mockedForce, mockedLogs]) {
    fn.mockReset()
  }
  setDemoSubject('S20260925000001')
  setActorMode('operator')
  mockedGovernance.mockResolvedValue(page([governanceItem(), governanceItem({
    contractNo: 'CO000014', status: 'TERMINATED', terminationType: 'GOVERNANCE_FORCE_TERMINATED',
  }), governanceItem({ contractNo: 'CO000015', status: 'NEGOTIATING' })]))
  mockedLogs.mockResolvedValue(page([actionLog()]))
})

describe('治理列表（T17 / R10）', () => {
  it('全量列表渲染：含协商中 / 已终止 + 终止类型 + 双方主体', async () => {
    await mountPage()
    const text = document.body.textContent
    expect(text).toContain('CO000013')
    expect(text).toContain('CO000014')
    expect(text).toContain('CO000015')
    expect(text).toContain('协商中')
    expect(text).toContain('已终止')
    expect(text).toContain('S20260925000001')
    expect(text).toContain('S20260922000001')
    expect(text).toContain('查看即留痕')
  })

  it('列表不可达（普通档 403）原样展示', async () => {
    setActorMode('subject')
    mockedGovernance.mockRejectedValue(new ApiError('1000C0005', '无权访问该资源'))
    await mountPage()
    expect(document.body.textContent).toContain('无权访问该资源')
  })
})

describe('治理直查详情（T17 / R11）', () => {
  it('直查渲染只读分区：总览 + 条款 + 策略 + 留痕；**无参与方动作条、无发起使用入口**', async () => {
    mockedGovernanceDetail.mockResolvedValue(governanceDetail())
    const wrapper = await mountPage()
    await button(wrapper, '查看治理详情')!.trigger('click')
    await flushPromises()
    expect(mockedGovernanceDetail).toHaveBeenCalledWith('CO000013')
    const text = document.body.textContent
    expect(text).toContain('CT000001')
    expect(text).toContain('风控建模')
    expect(text).toContain('治理查看')
    // 反向探针：治理页不得提供参与方动作与"发起使用"入口（t12 口径）
    expect(button(wrapper, '发起使用')).toBeFalsy()
    expect(button(wrapper, '提案')).toBeFalsy()
    expect(button(wrapper, '签署')).toBeFalsy()
  })

  it('直查对象缺失 → 同一条同形提示常量', async () => {
    mockedGovernanceDetail.mockRejectedValue(new ApiError('1008C0012', CONTRACT_NOT_ACCESSIBLE_TIP))
    const wrapper = await mountPage()
    await button(wrapper, '查看治理详情')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('合约不存在或不可见')
  })
})

describe('强制终止（T17 / W12）', () => {
  it('理由必填前置：空 = 零请求；填写后提交 {reason} 并刷新详情', async () => {
    mockedGovernanceDetail.mockResolvedValue(governanceDetail())
    const wrapper = await mountPage()
    await button(wrapper, '查看治理详情')!.trigger('click')
    await flushPromises()
    await clickBodyButton('强制终止')
    await flushPromises()
    await clickBodyButton('确定')
    await flushPromises()
    expect(mockedForce).not.toHaveBeenCalled()
    const reasonInput = wrapper.find('textarea, input[placeholder*="理由"]')
    await reasonInput.setValue('治理演示：违规使用')
    await clickBodyButton('确定')
    await flushPromises()
    expect(mockedForce).toHaveBeenCalledWith('CO000013', { reason: '治理演示：违规使用' })
  })

  it('成功后终止理由全文展示', async () => {
    mockedGovernanceDetail.mockResolvedValue(governanceDetail({
      status: 'TERMINATED',
      terminationType: 'GOVERNANCE_FORCE_TERMINATED',
      terminationReason: '治理演示：违规使用',
      terminatedBy: 'S20260925000001',
      endedAt: '2026-10-11T10:00:00',
    }))
    const wrapper = await mountPage()
    await button(wrapper, '查看治理详情')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('治理演示：违规使用')
    expect(document.body.textContent).toContain('S20260925000001')
  })
})
