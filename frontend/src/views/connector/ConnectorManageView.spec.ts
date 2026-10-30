import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import ConnectorManageView from './ConnectorManageView.vue'
import {
  applyConnector,
  amendConnector,
  listMyConnectors,
  listMyRegistrations,
  withdrawRegistration,
  heartbeat,
  type ConnectorRecord,
  type RegistrationRecord,
} from '../../api/connector'
import { ApiError, setDemoSubject } from '../../api/client'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'
import {
  APPLY_SUCCESS_TIP,
  CONNECTOR_EMPTY_GUIDE,
  CONNECTOR_EMPTY_TEXT,
  HEARTBEAT_SUCCESS_TIP,
  REGISTRATION_EMPTY_TEXT,
  WITHDRAW_CONFIRM_TEXT,
} from '../../constants/connector'

/**
 * 连接器管理页测试（WBS-3.6.7 hifi §3.1 / §8.1 T4/T5/T14）：
 * Tab1 注册申请（列表 / 筛选本页 / 申请弹窗零前端校验 / 补正预填 / 撤回二次确认 / 空态）；
 * Tab2 我的连接器（在线 tag / 最近心跳 / 上报心跳刷新 / 详情导航）；
 * 错误一律服务端文案原样展示（1010C0001 / 1010C0006 / 1010C0004）。
 */
vi.mock('../../api/connector', () => ({
  applyConnector: vi.fn(),
  amendConnector: vi.fn(),
  listMyRegistrations: vi.fn(),
  listMyConnectors: vi.fn(),
  withdrawRegistration: vi.fn(),
  heartbeat: vi.fn(),
}))

const mockedRegistrations = vi.mocked(listMyRegistrations)
const mockedConnectors = vi.mocked(listMyConnectors)
const mockedApply = vi.mocked(applyConnector)
const mockedAmend = vi.mocked(amendConnector)
const mockedWithdraw = vi.mocked(withdrawRegistration)
const mockedHeartbeat = vi.mocked(heartbeat)

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

function page<T>(list: T[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/connectors', name: 'connector-manage', component: ConnectorManageView },
      { path: '/connectors/:connectorNo', name: 'connector-detail', component: { template: '<div />' } },
    ],
  })
  await router.push('/connectors')
  await router.isReady()
  const wrapper = mount(ConnectorManageView, {
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
  for (const fn of [mockedRegistrations, mockedConnectors, mockedApply, mockedAmend, mockedWithdraw, mockedHeartbeat]) {
    fn.mockReset()
  }
  setDemoSubject('S20261026000041')
  if (!isDemoAuthed()) {
    signInDemo()
  }
  mockedRegistrations.mockResolvedValue(page([registration()]))
  mockedConnectors.mockResolvedValue(page([connector()]))
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('Tab1 注册申请（T4）', () => {
  it('进入即装载申请列表与已注册列表（服务端分页 pageSize=10）', async () => {
    await mountPage()
    expect(mockedRegistrations).toHaveBeenCalledWith(1, 10)
    expect(mockedConnectors).toHaveBeenCalledWith(1, 10)
    expect(document.body.textContent).toContain('CA000001')
    expect(document.body.textContent).toContain('待核验')
  })

  it('状态筛选为本页筛选：选择「已注册」后待核验行被隐藏', async () => {
    mockedRegistrations.mockResolvedValue(
      page([
        registration(),
        registration({ applicationNo: 'CA000002', connectorNo: 'CN000002', status: 'REGISTERED' }),
      ]),
    )
    const { wrapper } = await mountPage()
    expect(wrapper.findAll('.el-table__row').length).toBe(2)
    await wrapper.findComponent('.registration-status-select').setValue('REGISTERED')
    await flushPromises()
    const rows = wrapper.findAll('.el-table__row')
    expect(rows.length).toBe(1)
    expect(rows[0].text()).toContain('CA000002')
  })

  it('申请弹窗：留空三要素仍可提交（零前端业务校验），服务端文案在弹窗内原样展示', async () => {
    const { wrapper } = await mountPage()
    await button(wrapper, '申请接入')?.trigger('click')
    await flushPromises()
    mockedApply.mockRejectedValue(new ApiError('1010C0001', '连接器版本不能为空'))
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedApply).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('连接器版本不能为空')
  })

  it('申请成功：关闭弹窗、提示受理并刷新申请列表', async () => {
    const { wrapper } = await mountPage()
    mockedRegistrations.mockClear()
    await button(wrapper, '申请接入')?.trigger('click')
    await flushPromises()
    await wrapper.find('.apply-name input').setValue('新连接器')
    await wrapper.find('.apply-version input').setValue('1.0.0')
    mockedApply.mockResolvedValue(registration({ connectorName: '新连接器' }))
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedApply).toHaveBeenCalledWith({
      connectorName: '新连接器',
      connectorType: 'STANDARD',
      connectorVersion: '1.0.0',
    })
    expect(document.body.textContent).toContain(APPLY_SUCCESS_TIP)
    expect(mockedRegistrations).toHaveBeenCalled()
  })

  it('同名重复被拒（1010C0002）：弹窗内原样展示服务端文案', async () => {
    const { wrapper } = await mountPage()
    await button(wrapper, '申请接入')?.trigger('click')
    await flushPromises()
    mockedApply.mockRejectedValue(new ApiError('1010C0002', '同主体同名连接器已存在，请更换名称或走升级流程'))
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('同主体同名连接器已存在')
  })

  it('未入驻主体申请被拒（1010C0006）：弹窗内原样展示服务端文案', async () => {
    const { wrapper } = await mountPage()
    await button(wrapper, '申请接入')?.trigger('click')
    await flushPromises()
    mockedApply.mockRejectedValue(new ApiError('1010C0006', '主体未入驻或状态不可接入连接器'))
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('主体未入驻或状态不可接入连接器')
  })

  it('补正：弹窗预填当前三要素，提交 PUT 后刷新列表', async () => {
    const { wrapper } = await mountPage()
    await button(wrapper, '补正')?.trigger('click')
    await flushPromises()
    const nameInput = wrapper.find('.amend-name input')
    expect((nameInput.element as HTMLInputElement).value).toBe('星河数据连接器')
    mockedAmend.mockResolvedValue(registration({ connectorName: '补正后名称' }))
    await nameInput.setValue('补正后名称')
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedAmend).toHaveBeenCalledWith('CA000001', {
      connectorName: '补正后名称',
      connectorType: 'STANDARD',
      connectorVersion: '1.0.0',
    })
  })

  it('撤回：二次确认文案含终态说明；确认后调到 DELETE 并刷新', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const { wrapper } = await mountPage()
    mockedWithdraw.mockResolvedValue(registration({ status: 'CANCELLED' }))
    await button(wrapper, '撤回')?.trigger('click')
    await flushPromises()
    expect(confirmSpy).toHaveBeenCalled()
    expect(confirmSpy.mock.calls[0][0]).toBe(WITHDRAW_CONFIRM_TEXT)
    expect(mockedWithdraw).toHaveBeenCalledWith('CA000001')
  })

  it('申请列表分页：点击下一页以 pageNum=2 重查', async () => {
    mockedRegistrations.mockResolvedValue(page([registration()], 11))
    const { wrapper } = await mountPage()
    mockedRegistrations.mockClear()
    await wrapper.find('.el-pagination .btn-next').trigger('click')
    await flushPromises()
    expect(mockedRegistrations).toHaveBeenCalledWith(2, 10)
  })

  it('空态：暂无注册申请', async () => {
    mockedRegistrations.mockResolvedValue(page([]))
    await mountPage()
    expect(document.body.textContent).toContain(REGISTRATION_EMPTY_TEXT)
  })

  it('加载失败：服务端文案原样提示且不抛错（表格保持空）', async () => {
    mockedRegistrations.mockRejectedValue(new ApiError('1010S0001', '主体服务暂不可用，请稍后重试'))
    await mountPage()
    expect(document.body.textContent).toContain('主体服务暂不可用，请稍后重试')
  })
})

describe('Tab2 我的连接器（T5）', () => {
  it('在线 tag 与最近心跳渲染；非已注册态在线显示「—」', async () => {
    mockedConnectors.mockResolvedValue(
      page([connector(), connector({ connectorNo: 'CN000002', status: 'CANCELLED', online: false })]),
    )
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[1]?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('在线')
    expect(document.body.textContent).toContain('2026-10-30T10:00:00')
    const rows = wrapper.findAll('.el-table__row')
    const cancelledRow = rows.find((row) => row.text().includes('CN000002'))
    expect(cancelledRow?.text()).toContain('—')
    expect(cancelledRow?.text()).not.toContain('离线')
  })

  it('上报心跳：调到 POST 心跳并刷新列表 + 成功提示', async () => {
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[1]?.trigger('click')
    await flushPromises()
    mockedConnectors.mockClear()
    mockedHeartbeat.mockResolvedValue(connector())
    await button(wrapper, '上报心跳')?.trigger('click')
    await flushPromises()
    expect(mockedHeartbeat).toHaveBeenCalledWith('CN000001')
    expect(mockedConnectors).toHaveBeenCalled()
    expect(document.body.textContent).toContain(HEARTBEAT_SUCCESS_TIP)
  })

  it('心跳被拒（1010C0004）：服务端文案原样展示', async () => {
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[1]?.trigger('click')
    await flushPromises()
    mockedHeartbeat.mockRejectedValue(new ApiError('1010C0004', '当前状态不允许该操作'))
    await button(wrapper, '上报心跳')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('当前状态不允许该操作')
  })

  it('详情导航：跳到 /connectors/{no}', async () => {
    const { wrapper, router } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[1]?.trigger('click')
    await flushPromises()
    await button(wrapper, '详情')?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/connectors/CN000001')
  })

  it('空态 + 引导：暂无已注册连接器 / 先提交申请并经运营方核验', async () => {
    mockedConnectors.mockResolvedValue(page([]))
    const { wrapper } = await mountPage()
    await wrapper.findAll('.el-tabs__item')[1]?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(CONNECTOR_EMPTY_TEXT)
    expect(document.body.textContent).toContain(CONNECTOR_EMPTY_GUIDE)
  })

  it('申请列表「查看连接器」：带编号的已注册申请可跳详情', async () => {
    mockedRegistrations.mockResolvedValue(
      page([registration({ status: 'REGISTERED', connectorNo: 'CN000009' })]),
    )
    const { wrapper, router } = await mountPage()
    await button(wrapper, '查看连接器')?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/connectors/CN000009')
  })
})
