import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import SandboxDetailView from './SandboxDetailView.vue'
import {
  getSandbox,
  listSandboxJobs,
  listSandboxResults,
  listSandboxExports,
  closeSandbox,
  runSandboxJob,
  submitResultReview,
  exportSandboxResult,
  type SandboxRecord,
  type SandboxResultItem,
} from '../../api/delivery'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'
import {
  SANDBOX_EXIT_BOUNDARY_HINT,
  RESULT_STATUS_LABELS,
} from '../../constants/delivery'

/**
 * 沙箱任务详情深链页测试（WBS-3.5.6 hifi §6.3 / §7 T8/T9/T10）：
 * - T8 状态机：提交审核 × status 四态显隐矩阵 / 导出仅 APPROVED / 关闭仅 RUNNING / 驳回理由轮次；
 * - T9 工作台：唯一出口文案常驻 / jobId 白名单二项 / 越界 1008C0020 原样 / 作业历史；
 * - T10 导出：可选要素可空 / 幂等键 / 成功刷新 / 1009C0011 类原样。
 */
vi.mock('../../api/delivery', () => ({
  getSandbox: vi.fn(),
  listSandboxJobs: vi.fn(),
  listSandboxResults: vi.fn(),
  listSandboxExports: vi.fn(),
  closeSandbox: vi.fn(),
  runSandboxJob: vi.fn(),
  submitResultReview: vi.fn(),
  exportSandboxResult: vi.fn(),
}))

const mockedGet = vi.mocked(getSandbox)
const mockedJobs = vi.mocked(listSandboxJobs)
const mockedResults = vi.mocked(listSandboxResults)
const mockedExports = vi.mocked(listSandboxExports)
const mockedClose = vi.mocked(closeSandbox)
const mockedRun = vi.mocked(runSandboxJob)
const mockedSubmit = vi.mocked(submitResultReview)
const mockedExport = vi.mocked(exportSandboxResult)

function task(over: Partial<SandboxRecord> = {}): SandboxRecord {
  return {
    sandboxNo: 'SB000004',
    contractNo: 'CO000026',
    productId: 13,
    productName: '普惠金融数据服务',
    requesterNo: 'S20260922000001',
    providerNo: 'S20260925000001',
    status: 'RUNNING',
    cpus: 1,
    memoryMb: 512,
    ttlSeconds: 7200,
    expiresAt: '2026-10-24T21:52:29',
    dataFileCount: 1,
    closeReason: null,
    createdAt: '2026-10-24T21:53:29',
    ...over,
  }
}

function result(over: Partial<SandboxResultItem> = {}): SandboxResultItem {
  return {
    fileName: 'dataset-profile.md',
    sizeBytes: 182,
    checksum: '9388588ab840d110f4cbba6fe487b544b6bbabd8538fcd88f715228fb289e3b0',
    createdAt: '2026-10-24T21:57:03',
    status: null,
    conclusion: null,
    rejectReason: null,
    reviewRound: null,
    ...over,
  }
}

function page<T>(list: T[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage(route = '/sandboxes/SB000004') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/sandboxes/:sandboxNo',
        name: 'sandbox-detail',
        component: SandboxDetailView,
      },
    ],
  })
  await router.push(route)
  await router.isReady()
  const wrapper = mount(SandboxDetailView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return wrapper
}

function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((b) => b.text().includes(text))
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  for (const fn of [
    mockedGet,
    mockedJobs,
    mockedResults,
    mockedExports,
    mockedClose,
    mockedRun,
    mockedSubmit,
    mockedExport,
  ]) {
    fn.mockReset()
  }
  setDemoSubject('S20260922000001')
  setActorMode('subject')
  if (!isDemoAuthed()) {
    signInDemo()
  }
  mockedGet.mockResolvedValue(task())
  mockedJobs.mockResolvedValue(page([]))
  mockedResults.mockResolvedValue([])
  mockedExports.mockResolvedValue(page([]))
})

describe('页面装载与配额生命周期区（T8 前置）', () => {
  it('四请求并行装载（S2+S6+S7+S10）；基本信息要素渲染', async () => {
    mockedResults.mockResolvedValue([result()])
    await mountPage()
    expect(mockedGet).toHaveBeenCalledWith('SB000004')
    expect(mockedJobs).toHaveBeenCalledWith('SB000004', { pageNum: 1, pageSize: 10 })
    expect(mockedResults).toHaveBeenCalledWith('SB000004')
    expect(mockedExports).toHaveBeenCalledWith('SB000004', { pageNum: 1, pageSize: 10 })
    expect(document.body.textContent).toContain('SB000004')
    expect(document.body.textContent).toContain('1C·512MB·7200S')
    expect(document.body.textContent).toContain(SANDBOX_EXIT_BOUNDARY_HINT)
  })

  it('关闭任务按钮仅 RUNNING 显示；STOPPED 隐藏', async () => {
    mockedGet.mockResolvedValue(task({ status: 'STOPPED', closeReason: 'REQUESTER_CLOSE' }))
    const wrapper = await mountPage()
    expect(button(wrapper, '关闭任务')).toBeFalsy()
  })
})

describe('结果物区状态机（T8）', () => {
  it('未登记（status=null）：提交审核可用；导出禁用', async () => {
    mockedResults.mockResolvedValue([result()])
    const wrapper = await mountPage()
    expect(button(wrapper, '提交审核')).toBeTruthy()
    expect(button(wrapper, '导出')?.attributes('disabled')).toBeDefined()
    expect(document.body.textContent).toContain(RESULT_STATUS_LABELS[''])
  })

  it('PENDING：提交审核禁用（tooltip 审核中）；导出禁用', async () => {
    mockedResults.mockResolvedValue([result({ status: 'PENDING', conclusion: null })])
    const wrapper = await mountPage()
    expect(document.body.textContent).toContain(RESULT_STATUS_LABELS.PENDING)
    expect(button(wrapper, '导出')?.attributes('disabled')).toBeDefined()
  })

  it('REJECTED：驳回理由与轮次展示；可重新提交；导出禁用', async () => {
    mockedResults.mockResolvedValue([
      result({
        status: 'REJECTED',
        conclusion: 'REJECTED',
        rejectReason: 'output contains unmasked sample field',
        reviewRound: 1,
      }),
    ])
    const wrapper = await mountPage()
    expect(document.body.textContent).toContain('output contains unmasked sample field')
    expect(document.body.textContent).toContain('1')
    expect(button(wrapper, '导出')?.attributes('disabled')).toBeDefined()
  })

  it('APPROVED：导出可用；提交审核不出现（已通过不可再提交）', async () => {
    mockedResults.mockResolvedValue([
      result({ status: 'APPROVED', conclusion: 'APPROVED', reviewRound: 1 }),
    ])
    const wrapper = await mountPage()
    expect(button(wrapper, '提交审核')).toBeFalsy()
    const exportBtn = wrapper
      .findAll('button')
      .find((b) => b.text().trim() === '导出')
    expect(exportBtn).toBeTruthy()
    expect(exportBtn?.attributes('disabled')).toBeUndefined()
  })

  it('提交审核：fileName 编码 + 幂等键；成功刷新结果物', async () => {
    mockedResults.mockResolvedValue([result()])
    mockedSubmit.mockResolvedValue(result({ status: 'PENDING' }))
    const wrapper = await mountPage()
    await button(wrapper, '提交审核')?.trigger('click')
    await flushPromises()
    expect(mockedSubmit).toHaveBeenCalledTimes(1)
    const [key, sandboxNo, fileName] = mockedSubmit.mock.calls[0]
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
    expect(sandboxNo).toBe('SB000004')
    expect(fileName).toBe('dataset-profile.md')
    expect(mockedResults).toHaveBeenCalledTimes(2)
  })
})

describe('沙箱工作台（T9）', () => {
  it('运行作业弹窗：jobId 白名单下拉二项（数据集画像 / 输入清单）；提交契约逐字 + 幂等键', async () => {
    const wrapper = await mountPage()
    await button(wrapper, '运行作业')?.trigger('click')
    await flushPromises()
    const vm = wrapper.vm as unknown as { jobForm: { jobId: string } }
    vm.jobForm.jobId = 'dataset-profile'
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedRun).toHaveBeenCalledTimes(1)
    const [key, sandboxNo, command] = mockedRun.mock.calls[0]
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
    expect(sandboxNo).toBe('SB000004')
    expect(command.jobId).toBe('dataset-profile')
  })

  it('作业被拒（策略越界 1008C0020）：文案原样透传', async () => {
    mockedRun.mockRejectedValue(new ApiError('1008C0020', '用途或地域要素超出合约约定范围'))
    const wrapper = await mountPage()
    await button(wrapper, '运行作业')?.trigger('click')
    await flushPromises()
    ;(wrapper.vm as unknown as { jobForm: { jobId: string } }).jobForm.jobId = 'dataset-profile'
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('用途或地域要素超出合约约定范围')
  })
})

describe('导出弹窗与导出记录（T10）', () => {
  it('导出：可选要素可空提交（body 空）+ 幂等键；成功后导出记录区刷新并可见新行', async () => {
    mockedResults.mockResolvedValue([
      result({ status: 'APPROVED', conclusion: 'APPROVED', reviewRound: 1 }),
    ])
    mockedExport.mockResolvedValue({
      deliveryNo: 'DL000011',
      deliveryType: 'SANDBOX_EXPORT',
      contractNo: 'CO000026',
      sandboxNo: 'SB000004',
      resultName: 'dataset-profile.md',
      requesterNo: 'S20260922000001',
      providerNo: 'S20260925000001',
      fileName: 'dataset-profile.md',
      fileSize: 182,
      checksum: '9388588a',
      result: 'SUCCESS',
      createdAt: '2026-10-25T10:00:00',
    })
    const wrapper = await mountPage()
    const exportBtn = wrapper.findAll('button').find((b) => b.text().trim() === '导出')
    await exportBtn?.trigger('click')
    await flushPromises()
    await button(wrapper, '确认导出')?.trigger('click')
    await flushPromises()
    expect(mockedExport).toHaveBeenCalledTimes(1)
    const [key, sandboxNo, fileName, elements] = mockedExport.mock.calls[0]
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
    expect(sandboxNo).toBe('SB000004')
    expect(fileName).toBe('dataset-profile.md')
    expect(elements).toEqual({})
    expect(document.body.textContent).toContain('DL000011')
  })

  it('导出被拒（1009C0011 生命周期）：文案原样', async () => {
    mockedResults.mockResolvedValue([
      result({ status: 'APPROVED', conclusion: 'APPROVED', reviewRound: 1 }),
    ])
    mockedExport.mockRejectedValue(new ApiError('1009C0011', '沙箱任务当前状态不允许该操作'))
    const wrapper = await mountPage()
    const exportBtn = wrapper.findAll('button').find((b) => b.text().trim() === '导出')
    await exportBtn?.trigger('click')
    await flushPromises()
    await button(wrapper, '确认导出')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('沙箱任务当前状态不允许该操作')
  })
})
