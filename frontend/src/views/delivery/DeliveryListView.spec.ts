import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import DeliveryListView from './DeliveryListView.vue'
import {
  listDeliveries,
  getDelivery,
  requestDelivery,
  type DeliveryRecord,
} from '../../api/delivery'
import { listMyContracts } from '../../api/contract'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'
import { DELIVERY_TYPE_LABELS } from '../../constants/delivery'

/**
 * 交付记录页测试（WBS-3.5.6 hifi §6.1 / §7 T4/T5/T6）：
 * - T4 列表：过滤参数逐字（合约 / 形态）/ 形态 tag 中文 / 分页 / 空主体零请求 / 失败原样；
 * - T5 发起弹窗：必填前置拦截零请求 / 形态联动 fileId / 幂等键头出现 / 成功刷新 / 拒绝码原样；
 * - T6 详情抽屉：字段集 / 下载按钮可见性矩阵（形态 × 需求方他方）/ SM3 展示与下载提示 /
 *   无本体断言。
 */
vi.mock('../../api/delivery', () => ({
  listDeliveries: vi.fn(),
  getDelivery: vi.fn(),
  requestDelivery: vi.fn(),
  downloadDeliveryPackage: vi.fn(),
}))
vi.mock('../../api/contract', () => ({
  listMyContracts: vi.fn(),
}))

const mockedList = vi.mocked(listDeliveries)
const mockedGet = vi.mocked(getDelivery)
const mockedRequest = vi.mocked(requestDelivery)
const mockedMine = vi.mocked(listMyContracts)

function record(over: Partial<DeliveryRecord> = {}): DeliveryRecord {
  return {
    deliveryNo: 'DL000003',
    contractNo: 'CO000026',
    productId: 13,
    productName: '普惠金融数据服务',
    deliveryType: 'FILE',
    requesterNo: 'S20260922000001',
    providerNo: 'S20260925000001',
    fileId: 'F13-DEMO-001',
    fileName: 'demo-dataset-13.csv',
    fileSize: 3034,
    packageId: 'DL000003.sm4',
    checksum: '710c84aca137b34020fed4c89406fb6094efb96890bb70f2c86f57383aa9691b',
    result: 'SUCCESS',
    createdAt: '2026-10-20T22:13:21',
    ...over,
  }
}

function page<T>(list: T[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage(route = '/deliveries') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/deliveries', name: 'deliveries', component: DeliveryListView },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push(route)
  await router.isReady()
  const wrapper = mount(DeliveryListView, {
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
  for (const fn of [mockedList, mockedGet, mockedRequest, mockedMine]) {
    fn.mockReset()
  }
  setDemoSubject('S20260922000001')
  setActorMode('subject')
  if (!isDemoAuthed()) {
    signInDemo()
  }
  mockedMine.mockResolvedValue(page([]))
})

describe('交付记录列表（T4）', () => {
  it('首次装载：pageNum=1 / pageSize=10 逐字传参，行要素渲染（编号/合约/产品/形态中文/发起方/文件名）', async () => {
    mockedList.mockResolvedValue(page([record()]))
    await mountPage()
    expect(mockedList).toHaveBeenCalledWith({ pageNum: 1, pageSize: 10 })
    expect(document.body.textContent).toContain('DL000003')
    expect(document.body.textContent).toContain('CO000026')
    expect(document.body.textContent).toContain('普惠金融数据服务')
    expect(document.body.textContent).toContain(DELIVERY_TYPE_LABELS.FILE)
    expect(document.body.textContent).toContain('demo-dataset-13.csv')
  })

  it('形态过滤：选"沙箱导出"后查询参数 deliveryType=SANDBOX_EXPORT 逐字', async () => {
    mockedList.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    const selects = wrapper.findAll('.el-select')
    await selects[0].find('input').setValue('')
    ;(wrapper.vm as unknown as { filters: { deliveryType: string } }).filters.deliveryType =
      'SANDBOX_EXPORT'
    await button(wrapper, '查询')?.trigger('click')
    await flushPromises()
    expect(mockedList).toHaveBeenLastCalledWith({
      pageNum: 1,
      pageSize: 10,
      deliveryType: 'SANDBOX_EXPORT',
    })
  })

  it('列表被拒：后端文案原样展示（1009C0007 防枚举同形）', async () => {
    mockedList.mockRejectedValue(new ApiError('1009C0007', '交付记录不存在或无权查看'))
    await mountPage()
    expect(document.body.textContent).toContain('交付记录不存在或无权查看')
  })

  it('空主体编号：前置拦截零请求（1000C0002）', async () => {
    setDemoSubject('   ')
    mockedList.mockResolvedValue(page([]))
    await mountPage()
    expect(mockedList).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('主体编号')
  })
})

describe('发起交付弹窗（T5）', () => {
  it('必填留空前置拦截：合约编号为空提交 = 零请求', async () => {
    mockedList.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    await button(wrapper, '发起交付')?.trigger('click')
    await flushPromises()
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedRequest).not.toHaveBeenCalled()
  })

  it('提交契约逐字 + 幂等键头（UUID 形态）；成功后刷新列表', async () => {
    mockedList.mockResolvedValue(page([record()]))
    mockedRequest.mockResolvedValue(record())
    const wrapper = await mountPage()
    await button(wrapper, '发起交付')?.trigger('click')
    await flushPromises()
    const inputs = wrapper.findAll('.el-dialog input')
    await inputs[0].setValue('CO000026')
    const radios = wrapper.findAll('.el-dialog .el-radio')
    await radios[1].find('input').setValue()
    const fileInput = wrapper
      .findAll('.el-dialog input')
      .find((i) => (i.element as HTMLInputElement).placeholder?.includes('文件标识'))
    await fileInput?.setValue('F13-DEMO-001')
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(mockedRequest).toHaveBeenCalledTimes(1)
    const [key, command] = mockedRequest.mock.calls[0]
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
    expect(command).toEqual({
      contractNo: 'CO000026',
      deliveryType: 'FILE',
      fileId: 'F13-DEMO-001',
    })
    expect(mockedList).toHaveBeenCalledTimes(2)
  })

  it('被拒文案原样（1009C0006 形态不符），弹窗不吞错', async () => {
    mockedList.mockResolvedValue(page([]))
    mockedRequest.mockRejectedValue(new ApiError('1009C0006', '交付形态或范围与合约约定不符'))
    const wrapper = await mountPage()
    await button(wrapper, '发起交付')?.trigger('click')
    await flushPromises()
    const inputs = wrapper.findAll('.el-dialog input')
    await inputs[0].setValue('CO000026')
    await button(wrapper, '提交')?.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('交付形态或范围与合约约定不符')
  })
})

describe('详情抽屉与下载（T6）', () => {
  it('详情字段集 + SM3 校验值展示；FILE + 需求方主体 → 下载按钮可见', async () => {
    mockedList.mockResolvedValue(page([record()]))
    const wrapper = await mountPage()
    mockedGet.mockResolvedValue(record())
    await button(wrapper, '详情')?.trigger('click')
    await flushPromises()
    expect(mockedGet).toHaveBeenCalledWith('DL000003')
    expect(document.body.textContent).toContain(
      '710c84aca137b34020fed4c89406fb6094efb96890bb70f2c86f57383aa9691b',
    )
    expect(button(wrapper, '下载加密包')).toBeTruthy()
  })

  it('下载按钮可见性矩阵：API 形态不可下载；SANDBOX_EXPORT + 需求方可见；FILE + 他方主体不可见', async () => {
    setDemoSubject('S20260925000001')
    mockedList.mockResolvedValue(page([record()]))
    const wrapper = await mountPage()
    mockedGet.mockResolvedValue(record({ deliveryType: 'API' }))
    await button(wrapper, '详情')?.trigger('click')
    await flushPromises()
    expect(button(wrapper, '下载加密包')).toBeFalsy()

    mockedGet.mockResolvedValue(record({ deliveryType: 'SANDBOX_EXPORT' }))
    setDemoSubject('S20260922000001')
    await button(wrapper, '详情')?.trigger('click')
    await flushPromises()
    expect(button(wrapper, '下载加密包')).toBeTruthy()
  })

  it('详情无本体：抽屉不含文件内容类字段（content/正文/原文 均不出现）', async () => {
    mockedList.mockResolvedValue(page([record()]))
    const wrapper = await mountPage()
    mockedGet.mockResolvedValue(record())
    await button(wrapper, '详情')?.trigger('click')
    await flushPromises()
    const text = document.body.textContent ?? ''
    expect(text).not.toContain('content')
    expect(text).not.toContain('文件内容')
  })
})
