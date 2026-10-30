import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import ConnectorGovernanceView from './ConnectorGovernanceView.vue'
import {
  confirmUpgrade,
  listGovernanceConnectors,
  listPendingUpgrades,
  listReviewQueue,
  verifyRegistration,
  type ConnectorRecord,
  type RegistrationRecord,
  type VersionChangeRecord,
} from '../../api/connector'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'
import {
  CONFIRM_UPGRADE_TEXT,
  GOVERNANCE_EMPTY_TEXT,
  PENDING_UPGRADE_EMPTY_TEXT,
  REVIEW_QUEUE_EMPTY_TEXT,
} from '../../constants/connector'

/**
 * 连接器治理页测试（WBS-3.6.7 hifi §3.3 / §8.1 T10/T11/T12/T14）：
 * Tab1 全平台连接器（Q3-A：服务端状态筛选 / 在线 tag「—」口径 / 本页概览条 / 详情跳转带 from=governance）；
 * Tab2 注册核验（三查结论：通过含编号与绑定摘要；拒绝含不通过项与理由；并发 1010C0004 原样）；
 * Tab3 升级确认（显式确认 + 二次确认 + 刷新）。
 */
vi.mock('../../api/connector', () => ({
  listGovernanceConnectors: vi.fn(),
  listReviewQueue: vi.fn(),
  listPendingUpgrades: vi.fn(),
  verifyRegistration: vi.fn(),
  confirmUpgrade: vi.fn(),
}))

const mockedList = vi.mocked(listGovernanceConnectors)
const mockedQueue = vi.mocked(listReviewQueue)
const mockedPending = vi.mocked(listPendingUpgrades)
const mockedVerify = vi.mocked(verifyRegistration)
const mockedConfirm = vi.mocked(confirmUpgrade)

function connector(over: Partial<ConnectorRecord> = {}): ConnectorRecord {
  return {
    connectorNo: 'CN000001',
    subjectNo: 'S20261026000041',
    connectorName: '星河数据连接器',
    connectorType: 'STANDARD',
    connectorVersion: '1.0.0',
    status: 'REGISTERED',
    did: null,
    didStatus: null,
    verified: false,
    online: true,
    lastHeartbeatAt: '2026-10-30T10:00:00',
    updatedAt: '2026-10-30T10:00:00',
    ...over,
  }
}

function registration(over: Partial<RegistrationRecord> = {}): RegistrationRecord {
  return {
    applicationNo: 'CA000001',
    connectorNo: null,
    subjectNo: 'S20261026000041',
    connectorName: '星河数据连接器',
    connectorType: 'STANDARD',
    connectorVersion: '1.0.0',
    status: 'PENDING_REVIEW',
    lastRejectionCode: null,
    lastRejectionReason: null,
    createdAt: '2026-10-30T09:00:00',
    updatedAt: '2026-10-30T09:00:00',
    ...over,
  }
}

function upgrade(over: Partial<VersionChangeRecord> = {}): VersionChangeRecord {
  return {
    upgradeNo: 'UP000004',
    connectorNo: 'CN000001',
    subjectNo: 'S20261026000041',
    fromVersion: '1.0.0',
    toVersion: '2.0.0',
    compatibility: 'INCOMPATIBLE',
    status: 'PENDING_CONFIRM',
    requestedBy: 'S20261026000041',
    confirmedBy: null,
    appliedAt: null,
    createdAt: '2026-10-30T10:45:00',
    ...over,
  }
}

function page<T>(list: T[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/connector-governance', name: 'connector-governance', component: ConnectorGovernanceView },
      { path: '/connectors/:connectorNo', name: 'connector-detail', component: { template: '<div />' } },
    ],
  })
  await router.push('/connector-governance')
  await router.isReady()
  const wrapper = mount(ConnectorGovernanceView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((b) => b.text().includes(text))
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  for (const fn of [mockedList, mockedQueue, mockedPending, mockedVerify, mockedConfirm]) {
    fn.mockReset()
  }
  setDemoSubject('ops-admin')
  setActorMode('operator')
  if (!isDemoAuthed()) {
    signInDemo()
  }
  mockedList.mockResolvedValue(
    page([
      connector(),
      connector({
        connectorNo: null,
        connectorName: '待核验样件',
        status: 'PENDING_REVIEW',
        online: false,
        lastHeartbeatAt: null,
      }),
    ]),
  )
  mockedQueue.mockResolvedValue(page([registration()]))
  mockedPending.mockResolvedValue(page([upgrade()]))
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('Tab1 全平台连接器（T10 / Q3-A）', () => {
  it('进入即装载三队列；列表渲染状态与在线「—」口径', async () => {
    const { wrapper } = await mountPage()
    expect(mockedList).toHaveBeenCalledWith('', 1, 10)
    expect(mockedQueue).toHaveBeenCalledWith(1, 10)
    expect(mockedPending).toHaveBeenCalledWith(1, 10)
    expect(document.body.textContent).toContain('CN000001')
    const rows = wrapper.findAll('.el-table__row')
    const pendingRow = rows.find((row) => row.text().includes('待核验样件'))
    expect(pendingRow?.text()).toContain('待核验')
    expect(pendingRow?.find('.el-tag')?.text()).toBe('待核验')
    expect(pendingRow?.text()).toContain('—')
  })

  it('本页概览条：在线 X / 离线 Y（仅已注册态计入）', async () => {
    mockedList.mockResolvedValue(
      page([
        connector(),
        connector({ connectorNo: 'CN000003', online: false, lastHeartbeatAt: null }),
        connector({ connectorNo: 'CN000004', status: 'CANCELLED', online: false, lastHeartbeatAt: null }),
      ]),
    )
    await mountPage()
    const overview = document.querySelector('.overview-bar')
    expect(overview?.textContent).toContain('本页在线 1')
    expect(overview?.textContent).toContain('离线 1')
  })

  it('状态筛选为服务端筛选：选择「已注册」后以 status 参数重查', async () => {
    const { wrapper } = await mountPage()
    mockedList.mockClear()
    await wrapper.findComponent('.governance-status-select').setValue('REGISTERED')
    await flushPromises()
    expect(mockedList).toHaveBeenCalledWith('REGISTERED', 1, 10)
  })

  it('详情跳转：带 from=governance；无编号行不渲染详情入口', async () => {
    const { wrapper, router } = await mountPage()
    const rows = wrapper.findAll('.el-table__row')
    const registeredRow = rows.find((row) => row.text().includes('CN000001'))
    const pendingRow = rows.find((row) => row.text().includes('待核验样件'))
    expect(pendingRow?.findAll('button').some((b) => b.text().includes('详情'))).toBe(false)
    const detailBtn = registeredRow?.findAll('button').find((b) => b.text().includes('详情'))
    await detailBtn?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/connectors/CN000001?from=governance')
  })

  it('治理列表分页：点击下一页以 pageNum=2 重查（保留 status 过滤）', async () => {
    mockedList.mockResolvedValue(page([connector()], 11))
    const { wrapper } = await mountPage()
    mockedList.mockClear()
    await wrapper.find('.el-pagination .btn-next').trigger('click')
    await flushPromises()
    expect(mockedList).toHaveBeenCalledWith('', 2, 10)
  })

  it('空态：暂无连接器档案', async () => {
    mockedList.mockResolvedValue(page([]))
    await mountPage()
    expect(document.body.textContent).toContain(GOVERNANCE_EMPTY_TEXT)
  })

  it('加载失败：服务端文案原样（不吞错）', async () => {
    mockedList.mockRejectedValue(new ApiError('1000C0005', '无权限执行该操作'))
    await mountPage()
    expect(document.body.textContent).toContain('无权限执行该操作')
  })
})

describe('Tab2 注册核验（T11）', () => {
  it('待核验队列渲染（申请单号 / 主体 / 名称 / 时间）', async () => {
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[1]?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('CA000001')
    expect(document.body.textContent).toContain('S20261026000041')
  })

  it('核验通过：结论面板展示编号与绑定摘要，关闭后刷新队列', async () => {
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[1]?.trigger('click')
    await flushPromises()
    mockedVerify.mockResolvedValue({
      applicationNo: 'CA000001',
      verified: true,
      failedCheck: null,
      reasonCode: null,
      reason: null,
      connectorNo: 'CN000010',
      status: 'REGISTERED',
      binding: {
        connectorNo: 'CN000010',
        subjectNo: 'S20261026000041',
        did: 'did:ctds:S20261026000041',
        bindingStatus: 'ACTIVE',
        boundAt: '2026-10-30T11:00:00',
        didStatus: 'ACTIVE',
        verified: true,
      },
    })
    mockedQueue.mockClear()
    await button(wrapper, '核验')?.trigger('click')
    await flushPromises()
    expect(mockedVerify).toHaveBeenCalledWith('CA000001')
    expect(document.body.textContent).toContain('核验通过')
    expect(document.body.textContent).toContain('CN000010')
    expect(document.body.textContent).toContain('did:ctds:S20261026000041')
    // 关闭结论后刷新队列
    await button(wrapper, '关闭')?.trigger('click')
    await flushPromises()
    expect(mockedQueue).toHaveBeenCalled()
  })

  it('核验拒绝：不通过项中文 + 原因码 + 服务端理由原样 + 补正提示', async () => {
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[1]?.trigger('click')
    await flushPromises()
    mockedVerify.mockResolvedValue({
      applicationNo: 'CA000001',
      verified: false,
      failedCheck: 'SUBJECT_ADMISSION',
      reasonCode: '1010C0006',
      reason: '主体未入驻或状态不可接入连接器',
      connectorNo: null,
      status: 'PENDING_REVIEW',
      binding: null,
    })
    await button(wrapper, '核验')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('核验拒绝')
    expect(document.body.textContent).toContain('主体资格')
    expect(document.body.textContent).toContain('1010C0006')
    expect(document.body.textContent).toContain('主体未入驻或状态不可接入连接器')
    expect(document.body.textContent).toContain('补正后可重新核验')
  })

  it('并发已被处理（1010C0004）：服务端文案原样展示', async () => {
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[1]?.trigger('click')
    await flushPromises()
    mockedVerify.mockRejectedValue(new ApiError('1010C0004', '当前状态不允许该操作'))
    await button(wrapper, '核验')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('当前状态不允许该操作')
  })

  it('空态：暂无待核验申请', async () => {
    mockedQueue.mockResolvedValue(page([]))
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[1]?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(REVIEW_QUEUE_EMPTY_TEXT)
  })
})

describe('Tab3 升级确认（T12）', () => {
  it('待确认队列渲染（旧 → 新版本 / 提交人）', async () => {
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[2]?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('UP000004')
    expect(document.body.textContent).toContain('2.0.0')
  })

  it('确认：二次确认文案 + 显式确认并刷新队列', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[2]?.trigger('click')
    await flushPromises()
    mockedConfirm.mockResolvedValue(upgrade({ status: 'APPLIED', confirmedBy: 'ops-admin' }))
    mockedPending.mockClear()
    await button(wrapper, '确认')?.trigger('click')
    await flushPromises()
    expect(confirmSpy.mock.calls[0][0]).toBe(CONFIRM_UPGRADE_TEXT)
    expect(mockedConfirm).toHaveBeenCalledWith('CN000001', 'UP000004')
    expect(mockedPending).toHaveBeenCalled()
  })

  it('确认失败（1010C0004 并发）：服务端文案原样', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[2]?.trigger('click')
    await flushPromises()
    mockedConfirm.mockRejectedValue(new ApiError('1010C0004', '当前状态不允许该操作'))
    await button(wrapper, '确认')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('当前状态不允许该操作')
  })

  it('空态：暂无待确认升级', async () => {
    mockedPending.mockResolvedValue(page([]))
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[2]?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(PENDING_UPGRADE_EMPTY_TEXT)
  })
})
