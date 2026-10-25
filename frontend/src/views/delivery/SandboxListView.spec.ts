import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import SandboxListView from './SandboxListView.vue'
import { listSandboxes, requestSandbox, type SandboxRecord } from '../../api/delivery'
import { listMyContracts } from '../../api/contract'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'
import { SANDBOX_STATUS_LABELS, SANDBOX_FORM_HINT } from '../../constants/delivery'

/**
 * 沙箱任务页测试（WBS-3.5.6 hifi §6.2 / §7 T7）：
 * 过滤（合约 / 状态）/ 状态 tag 中文 / 配额摘要 / G1 口径提示常驻 / 申请弹窗幂等键 /
 * 成功跳深链 / 终态行关闭原因。
 */
vi.mock('../../api/delivery', () => ({
  listSandboxes: vi.fn(),
  requestSandbox: vi.fn(),
}))
vi.mock('../../api/contract', () => ({
  listMyContracts: vi.fn(),
}))

const mockedList = vi.mocked(listSandboxes)
const mockedRequest = vi.mocked(requestSandbox)
const mockedMine = vi.mocked(listMyContracts)

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

function page<T>(list: T[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 10, totalPages: 1 }
}

let currentRouter: Router | undefined

async function mountPage(route = '/sandboxes') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/sandboxes', name: 'sandboxes', component: SandboxListView },
      {
        path: '/sandboxes/:sandboxNo',
        name: 'sandbox-detail',
        component: { template: '<div />' },
      },
    ],
  })
  currentRouter = router
  await router.push(route)
  await router.isReady()
  const wrapper = mount(SandboxListView, {
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
  for (const fn of [mockedList, mockedRequest, mockedMine]) {
    fn.mockReset()
  }
  setDemoSubject('S20260922000001')
  setActorMode('subject')
  if (!isDemoAuthed()) {
    signInDemo()
  }
  mockedMine.mockResolvedValue(page([]))
})

describe('沙箱任务列表（T7）', () => {
  it('首次装载参数逐字；行要素渲染（任务号 / 状态中文 / 配额摘要 / 双方）', async () => {
    mockedList.mockResolvedValue(page([task()]))
    await mountPage()
    expect(mockedList).toHaveBeenCalledWith({ pageNum: 1, pageSize: 10 })
    expect(document.body.textContent).toContain('SB000004')
    expect(document.body.textContent).toContain(SANDBOX_STATUS_LABELS.RUNNING)
    expect(document.body.textContent).toContain('1C·512MB·7200S')
    expect(document.body.textContent).toContain('S20260922000001')
    expect(document.body.textContent).toContain('S20260925000001')
  })

  it('状态过滤：status=STOPPED 逐字传参；终态行关闭原因可见', async () => {
    mockedList.mockResolvedValue(
      page([task({ sandboxNo: 'SB000005', status: 'STOPPED', closeReason: 'REQUESTER_CLOSE' })]),
    )
    const wrapper = await mountPage()
    ;(wrapper.vm as unknown as { filters: { status: string } }).filters.status = 'STOPPED'
    await button(wrapper, '查询')?.trigger('click')
    await flushPromises()
    expect(mockedList).toHaveBeenLastCalledWith({
      pageNum: 1,
      pageSize: 10,
      status: 'STOPPED',
    })
    expect(document.body.textContent).toContain('REQUESTER_CLOSE')
  })

  it('申请沙箱弹窗：G1 口径提示常驻 + 必填前置零请求 + 提交幂等键（UUID 形态）', async () => {
    mockedList.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    await button(wrapper, '申请沙箱')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(SANDBOX_FORM_HINT)
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedRequest).not.toHaveBeenCalled()
    const inputs = wrapper.findAll('.el-dialog input')
    await inputs[0].setValue('CO000026')
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedRequest).toHaveBeenCalledTimes(1)
    const [key, command] = mockedRequest.mock.calls[0]
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
    expect(command).toEqual({ contractNo: 'CO000026' })
  })

  it('申请成功 → 跳转深链详情页（/sandboxes/{sandboxNo}）', async () => {
    mockedList.mockResolvedValue(page([]))
    mockedRequest.mockResolvedValue(task({ sandboxNo: 'SB000007' }))
    const wrapper = await mountPage()
    await button(wrapper, '申请沙箱')?.trigger('click')
    await flushPromises()
    const inputs = wrapper.findAll('.el-dialog input')
    await inputs[0].setValue('CO000026')
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(currentRouter?.currentRoute.value.path).toBe('/sandboxes/SB000007')
  })

  it('申请被拒：文案原样（1009C0011 同形组不叠加解释）', async () => {
    mockedList.mockResolvedValue(page([]))
    mockedRequest.mockRejectedValue(new ApiError('1009C0011', '沙箱任务当前状态不允许该操作'))
    const wrapper = await mountPage()
    await button(wrapper, '申请沙箱')?.trigger('click')
    await flushPromises()
    const inputs = wrapper.findAll('.el-dialog input')
    await inputs[0].setValue('CO000026')
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('沙箱任务当前状态不允许该操作')
  })
})
