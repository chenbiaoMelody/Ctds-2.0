import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import ConnectorDetailView from './ConnectorDetailView.vue'
import {
  cancelConnector,
  checkSource,
  confirmUpgrade,
  getBinding,
  getLifecycle,
  getSource,
  heartbeat,
  listActionLogs,
  listSources,
  registerSource,
  amendSource,
  requestUpgrade,
  type ActionLogRecord,
  type LifecycleRecord,
  type SourceRecord,
} from '../../api/connector'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'
import {
  BINDING_CHECK_TRACE_HINT,
  CANCEL_CONFIRM_TEXT,
  DID_STATUS_INVALID_TEXT,
  HEALTH_THRESHOLD_HINT,
  OPERATOR_SOURCE_HIDDEN_HINT,
  SELF_CHECK_NOT_YET,
  SELF_CHECK_PASSED,
  SOURCE_CREDENTIAL_MASK_HINT,
  UPGRADE_PENDING_TIP,
} from '../../constants/connector'

/**
 * 连接器详情页测试（WBS-3.6.7 hifi §3.2 / §8.1 T6~T9/T14）：
 * L1 单一数据源（进页零 DID 依赖）/ 在线「—」口径 / 绑定查验（ACTIVE 才可用）/
 * 版本管理（升级登记 + 版本链 + 待确认提示）/ 注销（二次确认 + 在途拒绝 + 已注销只读）/
 * 源接入（普通档可见、运营档隐藏 + 提示行；仅掩码；凭据密码框；自检结论）/ 留痕。
 */
vi.mock('../../api/connector', () => ({
  getLifecycle: vi.fn(),
  getBinding: vi.fn(),
  listActionLogs: vi.fn(),
  heartbeat: vi.fn(),
  requestUpgrade: vi.fn(),
  confirmUpgrade: vi.fn(),
  cancelConnector: vi.fn(),
  listSources: vi.fn(),
  getSource: vi.fn(),
  registerSource: vi.fn(),
  amendSource: vi.fn(),
  checkSource: vi.fn(),
}))

const mockedLifecycle = vi.mocked(getLifecycle)
const mockedBinding = vi.mocked(getBinding)
const mockedLogs = vi.mocked(listActionLogs)
const mockedHeartbeat = vi.mocked(heartbeat)
const mockedUpgrade = vi.mocked(requestUpgrade)
const mockedConfirm = vi.mocked(confirmUpgrade)
const mockedCancel = vi.mocked(cancelConnector)
const mockedSources = vi.mocked(listSources)
const mockedSource = vi.mocked(getSource)
const mockedRegisterSource = vi.mocked(registerSource)
const mockedAmendSource = vi.mocked(amendSource)
const mockedCheck = vi.mocked(checkSource)

function lifecycle(over: Partial<LifecycleRecord> = {}): LifecycleRecord {
  return {
    connectorNo: 'CN000001',
    subjectNo: 'S20261026000041',
    connectorName: '星河数据连接器',
    connectorType: 'STANDARD',
    connectorVersion: '1.0.0',
    status: 'REGISTERED',
    online: true,
    lastHeartbeatAt: '2026-10-30T10:00:00',
    cancelledAt: null,
    bindingStatus: 'ACTIVE',
    did: 'did:ctds:S20261026000041',
    boundAt: '2026-10-30T09:30:00',
    releasedAt: null,
    inProgressJobs: 0,
    pendingUpgrade: false,
    versionHistory: [],
    updatedAt: '2026-10-30T10:00:00',
    ...over,
  }
}

function log(over: Partial<ActionLogRecord> = {}): ActionLogRecord {
  return {
    applicationNo: 'CA000001',
    connectorNo: 'CN000001',
    operatorNo: 'S20261026000041',
    action: 'connector.apply',
    result: 'SUCCESS',
    reasonCode: null,
    detail: null,
    createdAt: '2026-10-30T09:00:00',
    ...over,
  }
}

function source(over: Partial<SourceRecord> = {}): SourceRecord {
  return {
    sourceNo: 'SR000003',
    connectorNo: 'CN000001',
    sourceType: 'DATABASE',
    endpoint: 'jdbc:mysql://127.0.0.1:3306/demo',
    credentialRef: 'connector-source-credential',
    credentialMasked: '******',
    createdAt: '2026-10-30T10:10:00',
    updatedAt: '2026-10-30T10:10:00',
    ...over,
  }
}

function page<T>(list: T[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage(from = '') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/connectors', name: 'connector-manage', component: { template: '<div />' } },
      { path: '/connector-governance', name: 'connector-governance', component: { template: '<div />' } },
      { path: '/connectors/:connectorNo', name: 'connector-detail', component: ConnectorDetailView },
    ],
  })
  await router.push(`/connectors/CN000001${from}`)
  await router.isReady()
  const wrapper = mount(ConnectorDetailView, {
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
  for (const fn of [
    mockedLifecycle,
    mockedBinding,
    mockedLogs,
    mockedHeartbeat,
    mockedUpgrade,
    mockedConfirm,
    mockedCancel,
    mockedSources,
    mockedSource,
    mockedRegisterSource,
    mockedAmendSource,
    mockedCheck,
  ]) {
    fn.mockReset()
  }
  setDemoSubject('S20261026000041')
  setActorMode('subject')
  if (!isDemoAuthed()) {
    signInDemo()
  }
  mockedLifecycle.mockResolvedValue(lifecycle())
  mockedLogs.mockResolvedValue(page([log()]))
  mockedSources.mockResolvedValue(page([source()]))
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('详情基础与 L1 单一数据源（T6）', () => {
  it('进入即调 L1 + 留痕第 1 页 + 源清单第 1 页（普通档）；基本信息全字段渲染', async () => {
    await mountPage()
    expect(mockedLifecycle).toHaveBeenCalledWith('CN000001')
    expect(mockedLogs).toHaveBeenCalledWith('CN000001', 1, 10)
    expect(mockedSources).toHaveBeenCalledWith('CN000001', 1, 10)
    // 进页零 DID 依赖：实时状态只经「绑定查验」显式触发
    expect(mockedBinding).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('星河数据连接器')
    expect(document.body.textContent).toContain('S20261026000041')
    expect(document.body.textContent).toContain('1.0.0')
    expect(document.body.textContent).toContain('已注册')
  })

  it('L1 失败：整页错误态 + 重试按钮；重试再次调 L1', async () => {
    mockedLifecycle.mockRejectedValue(new ApiError('1010C0003', '未找到该记录或无权访问'))
    const { wrapper } = await mountPage()
    expect(wrapper.find('.detail-error').exists()).toBe(true)
    expect(document.body.textContent).toContain('未找到该记录或无权访问')
    mockedLifecycle.mockResolvedValue(lifecycle())
    await button(wrapper, '重试')?.trigger('click')
    await flushPromises()
    expect(mockedLifecycle).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('星河数据连接器')
  })

  it('在线「—」口径：已注销档案不展示在线结论（不把历史心跳误读为在线）', async () => {
    mockedLifecycle.mockResolvedValue(
      lifecycle({ status: 'CANCELLED', online: false, cancelledAt: '2026-10-30T11:00:00' }),
    )
    await mountPage()
    const health = document.querySelector('.health-section')
    expect(health?.textContent).toContain('—')
    expect(health?.textContent).not.toContain('离线')
    expect(document.body.textContent).toContain('2026-10-30T11:00:00')
  })

  it('自治理页进入：返回链接指向连接器治理', async () => {
    await mountPage('?from=governance')
    expect(document.body.textContent).toContain('返回连接器治理')
  })

  it('留痕表：动作中文标签 + 结果 + 原因码；空态「暂无留痕」', async () => {
    mockedLogs.mockResolvedValue(
      page([
        log(),
        log({ action: 'source.check', result: 'DENIED', reasonCode: '1010C0010' }),
        log({ action: 'source.read', result: 'FAILURE', reasonCode: '1010S0004' }),
      ]),
    )
    const { wrapper } = await mountPage()
    expect(document.body.textContent).toContain('提交申请')
    expect(document.body.textContent).toContain('源自检')
    expect(document.body.textContent).toContain('1010C0010')
    expect(document.body.textContent).toContain('失败')
    expect(wrapper.findAll('.audit-section .el-table__row').length).toBe(3)
    expect(
      wrapper.findAll('.audit-section .el-tag').some((tag) => tag.classes().includes('el-tag--warning')),
    ).toBe(true)
  })
})

describe('详情分页（T6）', () => {
  it('留痕分页：点击下一页以 pageNum=2 重查', async () => {
    mockedLogs.mockResolvedValue(page([log()], 11))
    const { wrapper } = await mountPage()
    mockedLogs.mockClear()
    await wrapper.find('.audit-section .btn-next').trigger('click')
    await flushPromises()
    expect(mockedLogs).toHaveBeenCalledWith('CN000001', 2, 10)
  })
})

describe('健康度与绑定查验（T6）', () => {
  it('健康度区：阈值说明常驻 + 上报心跳（属主 + 已注册）刷新 L1', async () => {
    const { wrapper } = await mountPage()
    expect(document.body.textContent).toContain(HEALTH_THRESHOLD_HINT)
    mockedLifecycle.mockClear()
    mockedHeartbeat.mockResolvedValue({
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
      lastHeartbeatAt: '2026-10-30T10:30:00',
      updatedAt: '2026-10-30T10:30:00',
    })
    await button(wrapper, '上报心跳')?.trigger('click')
    await flushPromises()
    expect(mockedHeartbeat).toHaveBeenCalledWith('CN000001')
    expect(mockedLifecycle).toHaveBeenCalled()
  })

  it('绑定查验 ACTIVE：结果面板展示实时 DID 状态 + 留痕提示', async () => {
    const { wrapper } = await mountPage()
    mockedBinding.mockResolvedValue({
      connectorNo: 'CN000001',
      subjectNo: 'S20261026000041',
      did: 'did:ctds:S20261026000041',
      bindingStatus: 'ACTIVE',
      boundAt: '2026-10-30T09:30:00',
      didStatus: 'ACTIVE',
      verified: true,
    })
    await button(wrapper, '绑定查验')?.trigger('click')
    await flushPromises()
    expect(mockedBinding).toHaveBeenCalledWith('CN000001')
    expect(document.body.textContent).toContain(BINDING_CHECK_TRACE_HINT)
    expect(document.querySelector('.binding-result')?.textContent).toContain('有效')
  })

  it('DID 已吊销：verified=false 红字「DID 状态无效」', async () => {
    const { wrapper } = await mountPage()
    mockedBinding.mockResolvedValue({
      connectorNo: 'CN000001',
      subjectNo: 'S20261026000041',
      did: 'did:ctds:S20261026000041',
      bindingStatus: 'ACTIVE',
      boundAt: '2026-10-30T09:30:00',
      didStatus: 'REVOKED',
      verified: false,
    })
    await button(wrapper, '绑定查验')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(DID_STATUS_INVALID_TEXT)
  })

  it('DID 服务不可用（1010S0002）：错误文案原样展示，不冒充结论', async () => {
    const { wrapper } = await mountPage()
    mockedBinding.mockRejectedValue(new ApiError('1010S0002', 'DID 服务暂不可用，请稍后重试'))
    await button(wrapper, '绑定查验')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('DID 服务暂不可用，请稍后重试')
    expect(document.body.textContent).not.toContain(DID_STATUS_INVALID_TEXT)
  })

  it('RELEASED 绑定：查验按钮禁用且点击不发起请求（不制造必然失败请求）', async () => {
    mockedLifecycle.mockResolvedValue(
      lifecycle({ bindingStatus: 'RELEASED', releasedAt: '2026-10-30T11:00:00' }),
    )
    const { wrapper } = await mountPage()
    const checkBtn = button(wrapper, '绑定查验')
    expect(checkBtn?.attributes('disabled')).toBeDefined()
    await checkBtn?.trigger('click')
    await flushPromises()
    expect(mockedBinding).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('已解除')
    expect(document.body.textContent).toContain('2026-10-30T11:00:00')
  })
})

describe('版本管理（T7）', () => {
  it('升级登记（兼容）：提交刷新 L1；版本链展示确认人/生效时间', async () => {
    mockedLifecycle.mockResolvedValue(
      lifecycle({
        versionHistory: [
          {
            upgradeNo: 'UP000002',
            connectorNo: 'CN000001',
            subjectNo: 'S20261026000041',
            fromVersion: '1.0.0',
            toVersion: '1.1.0',
            compatibility: 'COMPATIBLE',
            status: 'APPLIED',
            requestedBy: 'S20261026000041',
            confirmedBy: null,
            appliedAt: '2026-10-30T10:20:00',
            createdAt: '2026-10-30T10:19:00',
          },
        ],
      }),
    )
    const { wrapper } = await mountPage()
    expect(document.body.textContent).toContain('已生效')
    expect(document.body.textContent).toContain('2026-10-30T10:20:00')
    await button(wrapper, '升级登记')?.trigger('click')
    await flushPromises()
    await wrapper.find('.upgrade-version input').setValue('1.2.0')
    mockedUpgrade.mockResolvedValue({
      upgradeNo: 'UP000003',
      connectorNo: 'CN000001',
      subjectNo: 'S20261026000041',
      fromVersion: '1.1.0',
      toVersion: '1.2.0',
      compatibility: 'COMPATIBLE',
      status: 'APPLIED',
      requestedBy: 'S20261026000041',
      confirmedBy: null,
      appliedAt: '2026-10-30T10:40:00',
      createdAt: '2026-10-30T10:40:00',
    })
    mockedLifecycle.mockClear()
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedUpgrade).toHaveBeenCalledWith('CN000001', {
      toVersion: '1.2.0',
      compatibility: 'COMPATIBLE',
    })
    expect(mockedLifecycle).toHaveBeenCalled()
  })

  it('不兼容升级：提示待确认；版本链状态「待确认」', async () => {
    mockedLifecycle.mockResolvedValue(
      lifecycle({
        pendingUpgrade: true,
        versionHistory: [
          {
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
          },
        ],
      }),
    )
    await mountPage()
    expect(document.body.textContent).toContain(UPGRADE_PENDING_TIP)
    expect(document.body.textContent).toContain('待确认')
  })

  it('目标版本格式非法（1010C0001）：服务端字段级文案原样展示', async () => {
    const { wrapper } = await mountPage()
    await button(wrapper, '升级登记')?.trigger('click')
    await flushPromises()
    await wrapper.find('.upgrade-version input').setValue('abc')
    mockedUpgrade.mockRejectedValue(new ApiError('1010C0001', '目标版本格式不合法'))
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('目标版本格式不合法')
  })

  it('版本相同被拒（1010C0009）：服务端文案原样展示', async () => {
    const { wrapper } = await mountPage()
    await button(wrapper, '升级登记')?.trigger('click')
    await flushPromises()
    await wrapper.find('.upgrade-version input').setValue('1.0.0')
    mockedUpgrade.mockRejectedValue(new ApiError('1010C0009', '目标版本与当前版本相同，无需升级'))
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('目标版本与当前版本相同，无需升级')
  })
})

describe('注销（T8）', () => {
  it('二次确认后注销成功：刷新 L1 为已注销、写入口消失、绑定解除记录可读', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const { wrapper } = await mountPage()
    mockedCancel.mockResolvedValue(
      lifecycle({
        status: 'CANCELLED',
        cancelledAt: '2026-10-30T11:30:00',
        bindingStatus: 'RELEASED',
        releasedAt: '2026-10-30T11:30:00',
      }),
    )
    mockedLifecycle.mockResolvedValue(
      lifecycle({
        status: 'CANCELLED',
        cancelledAt: '2026-10-30T11:30:00',
        bindingStatus: 'RELEASED',
        releasedAt: '2026-10-30T11:30:00',
      }),
    )
    await button(wrapper, '注销连接器')?.trigger('click')
    await flushPromises()
    expect(confirmSpy.mock.calls[0][0]).toBe(CANCEL_CONFIRM_TEXT)
    expect(mockedCancel).toHaveBeenCalledWith('CN000001')
    expect(mockedLifecycle).toHaveBeenCalled()
    expect(button(wrapper, '上报心跳')).toBeFalsy()
    expect(button(wrapper, '注销连接器')).toBeFalsy()
    expect(document.body.textContent).toContain('已注销')
    expect(document.body.textContent).toContain('已解除')
    expect(document.body.textContent).toContain('2026-10-30T11:30:00')
  })

  it('在途作业拒绝（1010C0008）：文案原样 + 在途提示可见', async () => {
    mockedLifecycle.mockResolvedValue(lifecycle({ inProgressJobs: 2 }))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const { wrapper } = await mountPage()
    expect(document.body.textContent).toContain('存在进行中的交付作业')
    mockedCancel.mockRejectedValue(
      new ApiError('1010C0008', '存在进行中的交付作业，请先完结或终止后再注销'),
    )
    await button(wrapper, '注销连接器')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('请先完结或终止后再注销')
  })

  it('已注销态：心跳 / 升级 / 注销 / 源写入口全部消失，留痕与版本链仍可读', async () => {
    mockedLifecycle.mockResolvedValue(
      lifecycle({ status: 'CANCELLED', cancelledAt: '2026-10-30T11:30:00', bindingStatus: 'RELEASED' }),
    )
    const { wrapper } = await mountPage()
    expect(button(wrapper, '上报心跳')).toBeFalsy()
    expect(button(wrapper, '升级登记')).toBeFalsy()
    expect(button(wrapper, '注销连接器')).toBeFalsy()
    expect(button(wrapper, '登记源')).toBeFalsy()
    expect(wrapper.find('.audit-section').exists()).toBe(true)
    expect(document.body.textContent).toContain('提交申请')
  })
})

describe('源接入（T9）', () => {
  it('普通档可见：四类源（含模拟源标注）/ 掩码 / 自检与授权提示常驻', async () => {
    const { wrapper } = await mountPage()
    expect(document.querySelector('.source-section')).toBeTruthy()
    expect(document.body.textContent).toContain('******')
    expect(document.body.textContent).toContain(SOURCE_CREDENTIAL_MASK_HINT)
    await button(wrapper, '登记源')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('政务共享交换平台（模拟源）')
    expect(document.body.textContent).toContain('数据库')
    expect(document.body.textContent).toContain('对象存储')
    expect(document.body.textContent).toContain('API 网关')
  })

  it('运营档：不渲染源接入区，仅展示口径提示行（DB-88 挂起）', async () => {
    setActorMode('operator')
    await mountPage()
    expect(document.querySelector('.source-section')).toBeFalsy()
    expect(document.body.textContent).toContain(OPERATOR_SOURCE_HIDDEN_HINT)
  })

  it('登记源：凭据为密码框；成功后清空并刷新清单', async () => {
    const { wrapper } = await mountPage()
    await button(wrapper, '登记源')?.trigger('click')
    await flushPromises()
    const credentialInput = wrapper.find('.source-credential input')
    expect(credentialInput.attributes('type')).toBe('password')
    await wrapper.find('.source-endpoint input').setValue('jdbc:mysql://127.0.0.1:3306/demo')
    await credentialInput.setValue('secret-001')
    mockedRegisterSource.mockResolvedValue(source())
    mockedSources.mockClear()
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedRegisterSource).toHaveBeenCalledWith('CN000001', {
      sourceType: 'DATABASE',
      endpoint: 'jdbc:mysql://127.0.0.1:3306/demo',
      credential: 'secret-001',
    })
    expect(mockedSources).toHaveBeenCalled()
    expect((credentialInput.element as HTMLInputElement).value).toBe('')
  })

  it('源地址被拒（1010C0010）：服务端文案原样展示', async () => {
    const { wrapper } = await mountPage()
    await button(wrapper, '登记源')?.trigger('click')
    await flushPromises()
    await wrapper.find('.source-endpoint input').setValue('jdbc:mysql://10.0.0.1:3306/x')
    await wrapper.find('.source-credential input').setValue('s')
    mockedRegisterSource.mockRejectedValue(
      new ApiError('1010C0010', '源地址不被允许（解析到受限网络或访问形态不支持）'),
    )
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('源地址不被允许')
  })

  it('凭据保护组件不可用（1010S0004）：服务端文案原样展示', async () => {
    const { wrapper } = await mountPage()
    await button(wrapper, '登记源')?.trigger('click')
    await flushPromises()
    await wrapper.find('.source-endpoint input').setValue('jdbc:mysql://127.0.0.1:3306/x')
    await wrapper.find('.source-credential input').setValue('s')
    mockedRegisterSource.mockRejectedValue(
      new ApiError('1010S0004', '源凭据保护组件不可用，暂不能登记或自检'),
    )
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('源凭据保护组件不可用')
  })

  it('变更：凭据留空 = 不修改（不传 credential 字段）', async () => {
    const { wrapper } = await mountPage()
    await button(wrapper, '变更')?.trigger('click')
    await flushPromises()
    const endpointInput = wrapper.find('.source-amend-endpoint input')
    expect((endpointInput.element as HTMLInputElement).value).toBe('jdbc:mysql://127.0.0.1:3306/demo')
    await endpointInput.setValue('jdbc:mysql://127.0.0.1:3306/demo2')
    mockedAmendSource.mockResolvedValue(source({ endpoint: 'jdbc:mysql://127.0.0.1:3306/demo2' }))
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedAmendSource).toHaveBeenCalledWith('CN000001', 'SR000003', {
      endpoint: 'jdbc:mysql://127.0.0.1:3306/demo2',
    })
  })

  it('自检失败：失败原因中文 + 服务端 message 原样', async () => {
    const { wrapper } = await mountPage()
    mockedCheck.mockResolvedValue({
      sourceNo: 'SR000003',
      passed: false,
      failureType: 'NETWORK_UNREACHABLE',
      message: '连接超时',
      checkedAt: '2026-10-30T10:50:00',
    })
    await button(wrapper, '自检')?.trigger('click')
    await flushPromises()
    expect(mockedCheck).toHaveBeenCalledWith('CN000001', 'SR000003')
    expect(document.body.textContent).toContain('网络不可达')
    expect(document.body.textContent).toContain('连接超时')
  })

  it('自检通过：绿色结论「自检通过」', async () => {
    const { wrapper } = await mountPage()
    mockedCheck.mockResolvedValue({
      sourceNo: 'SR000003',
      passed: true,
      failureType: null,
      message: 'ok',
      checkedAt: '2026-10-30T10:50:00',
    })
    await button(wrapper, '自检')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(SELF_CHECK_PASSED)
  })

  it('自检第三类失败（权限不足）：中文标签与源端原文分列展示', async () => {
    const { wrapper } = await mountPage()
    mockedCheck.mockResolvedValue({
      sourceNo: 'SR000003',
      passed: false,
      failureType: 'INSUFFICIENT_PERMISSION',
      message: 'Access denied for user',
      checkedAt: '2026-10-30T10:52:00',
    })
    await button(wrapper, '自检')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('权限不足')
    expect(document.body.textContent).toContain('Access denied for user')
  })

  it('最近自检：调源详情抽屉展示 lastCheck；空 = 尚未自检', async () => {
    const { wrapper } = await mountPage()
    mockedSource.mockResolvedValue(
      source({
        lastCheck: {
          passed: false,
          failureType: 'INVALID_CREDENTIAL',
          message: '凭据无效',
          checkedAt: '2026-10-30T10:55:00',
        },
      }),
    )
    await button(wrapper, '最近自检')?.trigger('click')
    await flushPromises()
    expect(mockedSource).toHaveBeenCalledWith('CN000001', 'SR000003')
    expect(document.body.textContent).toContain('凭据无效')
    mockedSource.mockResolvedValue(source())
    await button(wrapper, '最近自检')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(SELF_CHECK_NOT_YET)
    // failureType 缺省（后端 @JsonInclude(NON_NULL)：通过时字段不出现）也必须可渲染
    mockedSource.mockResolvedValue(
      source({ lastCheck: { passed: true, message: 'ok', checkedAt: '2026-10-30T10:56:00' } }),
    )
    await button(wrapper, '最近自检')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(SELF_CHECK_PASSED)
  })
})

describe('运营档详情（T7/T8 对照）', () => {
  it('运营档：升级登记与心跳入口隐藏（治理面只读 + 确认动作在治理页）', async () => {
    setActorMode('operator')
    const { wrapper } = await mountPage()
    expect(button(wrapper, '升级登记')).toBeFalsy()
    expect(button(wrapper, '上报心跳')).toBeFalsy()
    // 运营方仍可注销（connector.cancel）与查验绑定（connector.read）
    expect(button(wrapper, '注销连接器')).toBeTruthy()
    expect(button(wrapper, '绑定查验')).toBeTruthy()
    // 运营档未带 from=governance 也回治理页（复审 P1-1 锚）
    expect(document.body.textContent).toContain('返回连接器治理')
  })
})
