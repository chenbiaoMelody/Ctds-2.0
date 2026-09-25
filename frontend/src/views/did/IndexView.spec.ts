import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox, ElSelect } from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import IndexView from './IndexView.vue'
import {
  fetchDidRecords,
  fetchOperationLogs,
  reissueDid,
  resolveDid,
  retryIssuance,
  revokeDid,
} from '../../api/did'
import { ApiError } from '../../api/client'
import { RECORD_STATUS_FILTER_OPTIONS, RECORD_STATUS_LABELS } from '../../constants/did'

/**
 * WBS-3.1.11 DID 管理页测试（hifi T10~T13）：清单渲染/空态/筛选与分页透传、
 * 吊销两段式（理由必填零请求 + 二次确认取消零请求 + 确认后调用与刷新）、
 * 行内操作按记录状态派生、后端兜底文案如实透传、演示入口跳转。
 */
vi.mock('../../api/did', () => ({
  fetchDidRecords: vi.fn(),
  fetchOperationLogs: vi.fn(),
  fetchVerificationLogs: vi.fn(),
  resolveDid: vi.fn(),
  demoSign: vi.fn(),
  verifySignature: vi.fn(),
  revokeDid: vi.fn(),
  reissueDid: vi.fn(),
  retryIssuance: vi.fn(),
}))

const mockedRecords = vi.mocked(fetchDidRecords)
const mockedLogs = vi.mocked(fetchOperationLogs)
const mockedRevoke = vi.mocked(revokeDid)
const mockedReissue = vi.mocked(reissueDid)
const mockedRetry = vi.mocked(retryIssuance)
const mockedResolve = vi.mocked(resolveDid)

const activeRow = {
  subjectNo: 'S20260925000001',
  issuanceSeq: 1,
  did: 'did:ctds:S20260925000001.1',
  status: 'ACTIVE' as const,
  keyRef: 'did-S20260925000001-1',
  createdAt: '2026-09-25T10:00:00',
  updatedAt: '2026-09-25T10:00:00',
}

const pendingRow = {
  subjectNo: 'S20260925000002',
  issuanceSeq: 1,
  did: null,
  status: 'PENDING_ISSUE' as const,
  keyRef: null,
  createdAt: '2026-09-25T10:05:00',
  updatedAt: '2026-09-25T10:05:00',
}

const revokedRow = {
  subjectNo: 'S20260925000003',
  issuanceSeq: 1,
  did: 'did:ctds:S20260925000003.1',
  status: 'REVOKED' as const,
  keyRef: 'did-S20260925000003-1',
  createdAt: '2026-09-25T10:10:00',
  updatedAt: '2026-09-25T11:00:00',
}

const page = (list: unknown[]) => ({ list, total: list.length, pageNum: 1, pageSize: 10, totalPages: 1 })

const mountPage = async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/did', name: 'did-management', component: { template: '<div />' } },
      { path: '/did/demo', name: 'did-demo', component: { template: '<div />' } },
    ],
  })
  const wrapper = mount(IndexView, { global: { plugins: [ElementPlus, router] } })
  await flushPromises()
  return { wrapper, router }
}

const buttonByText = (wrapper: ReturnType<typeof mount>, text: string) =>
  wrapper.findAll('button').find((button) => button.text() === text)

describe('DID 管理页 · 记录清单（WBS-3.1.11 T10）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('渲染记录清单：主体编号/签发序号/DID/状态标签（三值）/密钥引用/签发时间', async () => {
    mockedRecords.mockResolvedValue(page([activeRow, revokedRow, pendingRow]) as never)
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('S20260925000001')
    expect(wrapper.text()).toContain('did:ctds:S20260925000001.1')
    expect(wrapper.text()).toContain('有效')
    expect(wrapper.text()).toContain('已吊销')
    // PENDING_ISSUE = 记录中间态，界面文案显式标注（不冒充 DID 状态）
    expect(wrapper.text()).toContain('待签发（记录中间态）')
    expect(wrapper.text()).toContain('did-S20260925000001-1')
    expect(wrapper.text()).toContain('2026-09-25T10:00:00')
  })

  it('状态文案经常量收口且与设计口径逐字一致（hifi §6.4；R2 修复）', async () => {
    mockedRecords.mockResolvedValue(page([activeRow, revokedRow, pendingRow]) as never)
    await mountPage()

    // 常量即契约：三值文案逐字锁定（防止"待签发"与"待签发（记录中间态）"再次漂移）
    expect(RECORD_STATUS_LABELS).toEqual({
      ACTIVE: '有效',
      REVOKED: '已吊销',
      PENDING_ISSUE: '待签发（记录中间态）',
    })
    // 筛选下拉选项全部取自同一常量（页面不得散写状态中文）
    expect(RECORD_STATUS_FILTER_OPTIONS).toEqual([
      { label: '全部', value: '' },
      { label: '有效', value: 'ACTIVE' },
      { label: '已吊销', value: 'REVOKED' },
      { label: '待签发（记录中间态）', value: 'PENDING_ISSUE' },
    ])
  })

  it('状态三值集合单一口径：筛选取值集 = 状态标签键集（F5 类型收口）', () => {
    // F5 收口后：`RecordStatusFilterValue = '' | DidRecordStatus` 由类型保证；
    // 运行时再钉一次"筛选选项取值集 ⊆/= 状态标签键集"（增删状态值而漏改任一侧即红）
    const labelKeys = Object.keys(RECORD_STATUS_LABELS).sort()
    const optionValues = RECORD_STATUS_FILTER_OPTIONS.map((option) => option.value)
      .filter((value) => value !== '')
      .sort()

    expect(optionValues).toEqual(labelKeys)
    for (const value of optionValues) {
      expect(RECORD_STATUS_LABELS[value]).toBeTruthy()
    }
  })

  it('无记录时展示空态文案（未入驻主体不签发 DID，非报错）', async () => {
    mockedRecords.mockResolvedValue(page([]) as never)
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('未找到符合条件的主体 DID 记录（未入驻主体不签发 DID）')
    expect(wrapper.find('.el-table__empty-block').exists()).toBe(true)
  })

  it('清单加载失败：如实展示后端业务文案', async () => {
    mockedRecords.mockRejectedValue(new ApiError('1000C0005', '无权限执行该操作'))
    await mountPage()

    expect(document.body.textContent).toContain('无权限执行该操作')
  })

  it('筛选与分页参数透传：默认首页 10 条；查询带主体编号与状态；翻页带新页码', async () => {
    mockedRecords.mockResolvedValue(page([activeRow]) as never)
    const { wrapper } = await mountPage()
    expect(mockedRecords).toHaveBeenCalledWith({ subjectNo: '', status: '', pageNum: 1, pageSize: 10 })

    await wrapper.find('.filter-subject input').setValue('S20260925000001')
    await wrapper.findComponent(ElSelect).setValue('ACTIVE')
    await wrapper.find('.query-btn').trigger('click')
    await flushPromises()
    expect(mockedRecords).toHaveBeenLastCalledWith({
      subjectNo: 'S20260925000001',
      status: 'ACTIVE',
      pageNum: 1,
      pageSize: 10,
    })

    mockedRecords.mockResolvedValue({
      list: [activeRow],
      total: 11,
      pageNum: 1,
      pageSize: 10,
      totalPages: 2,
    } as never)
    await wrapper.find('.reset-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.el-pagination .btn-next').trigger('click')
    await flushPromises()
    expect(mockedRecords).toHaveBeenLastCalledWith({ subjectNo: '', status: '', pageNum: 2, pageSize: 10 })
  })

  it('分页越界：末页下一页禁用，越界页码不发出请求（F1）', async () => {
    // 既有分页契约：页码按当前页透传；越界保护由 total/pageSize 驱动的"下一页禁用"承担（不自行纠偏页码）
    mockedRecords.mockResolvedValue({
      list: [activeRow],
      total: 11,
      pageNum: 1,
      pageSize: 10,
      totalPages: 2,
    } as never)
    const { wrapper } = await mountPage()

    const nextOfPage1 = wrapper.find('.el-pagination .btn-next')
    expect(
      nextOfPage1.attributes('disabled') !== undefined || nextOfPage1.classes().includes('is-disabled'),
    ).toBe(false)

    await nextOfPage1.trigger('click')
    await flushPromises()
    expect(mockedRecords).toHaveBeenLastCalledWith({ subjectNo: '', status: '', pageNum: 2, pageSize: 10 })

    // 末页（第 2 页 / 共 2 页）：下一页禁用 → 再点击不产生任何新请求
    const callsAtLastPage = mockedRecords.mock.calls.length
    const nextAtLastPage = wrapper.find('.el-pagination .btn-next')
    expect(
      nextAtLastPage.attributes('disabled') !== undefined ||
        nextAtLastPage.classes().includes('is-disabled'),
    ).toBe(true)
    await nextAtLastPage.trigger('click')
    await flushPromises()
    expect(mockedRecords.mock.calls.length).toBe(callsAtLastPage)
  })
})

describe('DID 管理页 · 吊销两段式（WBS-3.1.11 T11/T12，规格行为 4）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mockedRecords.mockResolvedValue(page([activeRow]) as never)
    mockedLogs.mockResolvedValue([] as never)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('理由留空提交：提示"吊销理由必填"且不发任何请求', async () => {
    const { wrapper } = await mountPage()
    await buttonByText(wrapper, '吊销')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('吊销不可逆')

    await wrapper.find('.revoke-next-btn').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('吊销理由必填')
    expect(mockedRevoke).not.toHaveBeenCalled()
  })

  it('填理由后二次确认：取消 → 零请求（状态不变、无留痕）', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)
    const { wrapper } = await mountPage()
    await buttonByText(wrapper, '吊销')!.trigger('click')
    await flushPromises()
    await wrapper.find('.revoke-reason textarea').setValue('私钥疑似泄露')
    await wrapper.find('.revoke-next-btn').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalled()
    expect(String(confirm.mock.calls[0][0])).toContain('吊销不可逆')
    expect(mockedRevoke).not.toHaveBeenCalled()
  })

  it('填理由后二次确认：取消 → 留痕零新增（独立于"零请求"的断言，F3）', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)
    mockedLogs.mockResolvedValue([
      {
        operation: 'ISSUE',
        operator: 'SYSTEM',
        reason: null,
        keyRef: activeRow.keyRef,
        statusFrom: 'PENDING_ISSUE',
        statusTo: 'ACTIVE',
        occurredAt: '2026-09-25T10:00:00',
      },
    ] as never)
    const { wrapper } = await mountPage()
    const loadsBefore = mockedRecords.mock.calls.length

    await buttonByText(wrapper, '吊销')!.trigger('click')
    await flushPromises()
    await wrapper.find('.revoke-reason textarea').setValue('私钥疑似泄露')
    await wrapper.find('.revoke-next-btn').trigger('click')
    await flushPromises()

    // ① 清单未刷新：界面不做任何乐观更新（与吊销留痕"服务器为准"口径一致）
    expect(mockedRecords.mock.calls.length).toBe(loadsBefore)

    // ② 详情留痕仍为服务端原值：只有签发一条，且界面不出现被取消的吊销理由
    await buttonByText(wrapper, '查看详情')!.trigger('click')
    await flushPromises()
    expect(mockedLogs).toHaveBeenCalledWith(activeRow.did)
    expect(wrapper.find('.el-drawer').text()).toContain('签发')
    expect(document.body.textContent).not.toContain('私钥疑似泄露')
  })

  it('填理由后二次确认：确认 → 调吊销接口并刷新清单', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mockedRevoke.mockResolvedValue({ did: activeRow.did, status: 'REVOKED', revokedAt: '2026-09-25T12:00:00' })
    const { wrapper } = await mountPage()
    await buttonByText(wrapper, '吊销')!.trigger('click')
    await flushPromises()
    await wrapper.find('.revoke-reason textarea').setValue('  私钥疑似泄露  ')
    await wrapper.find('.revoke-next-btn').trigger('click')
    await flushPromises()

    // 理由去首尾空白后提交
    expect(mockedRevoke).toHaveBeenCalledWith(activeRow.did, '私钥疑似泄露')
    // 成功后刷新清单（初次加载 + 刷新）
    expect(mockedRecords.mock.calls.length).toBeGreaterThanOrEqual(2)
  })

  it('后端兜底文案如实透传（1005C0002 / 1005C0003 不吞不改写）', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mockedRevoke.mockRejectedValue(new ApiError('1005C0003', '非有效 DID 不可吊销'))
    const { wrapper } = await mountPage()
    await buttonByText(wrapper, '吊销')!.trigger('click')
    await flushPromises()
    await wrapper.find('.revoke-reason textarea').setValue('重试吊销')
    await wrapper.find('.revoke-next-btn').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('非有效 DID 不可吊销')
  })
})

describe('DID 管理页 · 行内操作与详情（WBS-3.1.11 T13）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mockedLogs.mockResolvedValue([] as never)
  })

  it('行内操作按记录状态派生：有效→吊销；已吊销→重签；待签发→重试（无恢复入口）', async () => {
    mockedRecords.mockResolvedValue(page([activeRow, revokedRow, pendingRow]) as never)
    const { wrapper } = await mountPage()

    expect(buttonByText(wrapper, '吊销')).toBeTruthy()
    expect(buttonByText(wrapper, '重签')).toBeTruthy()
    expect(buttonByText(wrapper, '重试')).toBeTruthy()
    expect(wrapper.text()).not.toContain('恢复')
  })

  it('重签：二次确认后调重签接口并刷新清单', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mockedRecords.mockResolvedValue(page([revokedRow]) as never)
    mockedReissue.mockResolvedValue({
      did: 'did:ctds:S20260925000003.2',
      status: 'ACTIVE',
      keyRef: 'did-S20260925000003-2',
      issuedAt: '2026-09-25T12:00:00',
    })
    const { wrapper } = await mountPage()
    await buttonByText(wrapper, '重签')!.trigger('click')
    await flushPromises()

    expect(mockedReissue).toHaveBeenCalledWith(revokedRow.subjectNo)
    expect(mockedRecords.mock.calls.length).toBeGreaterThanOrEqual(2)
  })

  it('重试：二次确认后调重试接口，失败文案（1005B0001）如实展示', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mockedRecords.mockResolvedValue(page([pendingRow]) as never)
    mockedRetry.mockRejectedValue(new ApiError('1005B0001', '未找到待签发记录'))
    const { wrapper } = await mountPage()
    await buttonByText(wrapper, '重试')!.trigger('click')
    await flushPromises()

    expect(mockedRetry).toHaveBeenCalledWith(pendingRow.subjectNo)
    expect(document.body.textContent).toContain('未找到待签发记录')
  })

  it('查看详情：加载操作留痕（五要素列）；待签发记录无 DID → 展示"暂无操作留痕"且不发请求', async () => {
    mockedRecords.mockResolvedValue(page([activeRow]) as never)
    mockedLogs.mockResolvedValue([
      {
        operation: 'ISSUE',
        operator: 'SYSTEM',
        reason: null,
        keyRef: 'did-S20260925000001-1',
        statusFrom: 'PENDING_ISSUE',
        statusTo: 'ACTIVE',
        occurredAt: '2026-09-25T10:00:00',
      },
    ] as never)
    mockedResolve.mockResolvedValue({
      did: activeRow.did,
      status: 'ACTIVE',
      document: { publicKey: { type: 'SM2', valueHex: '04ab' }, controller: 'S20260925000001' },
    } as never)
    const { wrapper } = await mountPage()
    await buttonByText(wrapper, '查看详情')!.trigger('click')
    await flushPromises()

    expect(mockedLogs).toHaveBeenCalledWith(activeRow.did)
    expect(wrapper.text()).toContain('签发')
    expect(wrapper.text()).toContain('SYSTEM')
    // 详情区固定提示：只见公开要素，界面不展示任何私钥
    expect(wrapper.text()).toContain('仅公开要素，界面不展示任何私钥')
  })

  it('待签发记录打开详情：不发操作留痕请求，展示"暂无操作留痕"', async () => {
    mockedRecords.mockResolvedValue(page([pendingRow]) as never)
    const { wrapper } = await mountPage()
    await buttonByText(wrapper, '查看详情')!.trigger('click')
    await flushPromises()

    expect(mockedLogs).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('暂无操作留痕')
  })

  it('演示与验证入口：跳转携带当前列表目标 DID（hifi §6.3；R1 修复）', async () => {
    mockedRecords.mockResolvedValue(page([activeRow]) as never)
    const { wrapper, router } = await mountPage()
    await wrapper.find('.demo-entry-btn').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('did-demo')
    expect(router.currentRoute.value.query.did).toBe(activeRow.did)
  })

  it('演示与验证入口：列表无可用 DID 时不携带参数（待签发记录 did 为空）', async () => {
    mockedRecords.mockResolvedValue(page([pendingRow]) as never)
    const { wrapper, router } = await mountPage()
    await wrapper.find('.demo-entry-btn').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('did-demo')
    expect(router.currentRoute.value.query.did).toBeUndefined()
  })

  it('详情抽屉的演示入口：携带该记录 DID', async () => {
    mockedRecords.mockResolvedValue(page([activeRow]) as never)
    mockedLogs.mockResolvedValue([] as never)
    const { wrapper, router } = await mountPage()
    await buttonByText(wrapper, '查看详情')!.trigger('click')
    await flushPromises()
    await wrapper.find('.drawer-demo-btn').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('did-demo')
    expect(router.currentRoute.value.query.did).toBe(activeRow.did)
  })
})
