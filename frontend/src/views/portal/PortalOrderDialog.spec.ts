import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import PortalOrderDialog from './PortalOrderDialog.vue'
import { createOrder } from '../../api/order'
import { listTemplates, getTemplate } from '../../api/contract'
import { ApiError } from '../../api/client'
import type { ContractTemplateItem, TemplateDetail } from '../../api/contract'

/**
 * 订购弹窗测试（WBS-3.7.3 hifi §4.2 / §6.2 T2~T4；C-7.1 S2-1/S2-2 界面锚）：
 * - 打开拉启用模板列表（pageNum=1/pageSize=50）；选中后 getTemplate 拉条款框架；
 * - 必填前置：模板未选 / 必填槽位未填 → 零请求提示（沿 3.4.7 发起弹窗口径）；
 * - 幂等：弹窗生命周期内 requestId 复用（失败重提同键；hifi §4.2）；
 * - 提交成功 emit('ordered', orderNo)（路由跳转由父组件承担）；
 * - 失败：后端文案原样展示，弹窗状态不变（无乐观更新）。
 */
vi.mock('../../api/order', () => ({ createOrder: vi.fn() }))
vi.mock('../../api/contract', () => ({
  listTemplates: vi.fn(),
  getTemplate: vi.fn(),
}))

const mockedCreate = vi.mocked(createOrder)
const mockedTemplates = vi.mocked(listTemplates)
const mockedTemplate = vi.mocked(getTemplate)

const ORDER_RESULT = {
  orderNo: 'OD000001',
  status: 'IN_PROGRESS',
  contractNo: 'CO000021',
  contractStatus: 'NEGOTIATING',
  productId: 15,
  productName: 'W373 Demo API Feed',
  providerSubjectNo: 'S20260925000001',
  requesterSubjectNo: 'S20260925000002',
  pricingModel: 'PER_CALL',
  priceAmount: '3.00',
  templateNo: 'CT000001',
  templateVersionNo: 1,
  createdAt: '2026-11-01T10:00:00',
}

const TEMPLATE_ITEM: ContractTemplateItem = {
  templateNo: 'CT000001',
  name: '公共数据授权模板',
  type: { code: 'PUBLIC_DATA_AUTHORIZATION', displayName: '公共数据授权' },
  currentVersion: 1,
}

const TEMPLATE_DETAIL: TemplateDetail = {
  templateNo: 'CT000001',
  name: '公共数据授权模板',
  type: { code: 'PUBLIC_DATA_AUTHORIZATION', displayName: '公共数据授权' },
  currentVersion: 1,
  clauseFramework: {
    slots: [
      { key: 'subject_matter', name: '数据标的', required: true, guide: '描述授权数据' },
      { key: 'scope', name: '使用范围', required: true, guide: null },
    ],
  },
  publishedAt: '2026-10-20T10:00:00',
}

function templatePage() {
  return { list: [TEMPLATE_ITEM], total: 1, pageNum: 1, pageSize: 50, totalPages: 1 }
}

async function mountDialog() {
  const wrapper = mount(PortalOrderDialog, {
    props: {
      productId: 15,
      productName: 'W373 Demo API Feed',
      pricingModel: 'PER_CALL',
      priceAmount: '3.00',
    },
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

async function selectTemplateAndFillSlot(wrapper: Awaited<ReturnType<typeof mountDialog>>) {
  // 选择模板（直接对 ElSelect 发 update:modelValue + change——组件级事件，绕开浮层 DOM）
  const select = wrapper.findComponent({ name: 'ElSelect' })
  select.vm.$emit('update:modelValue', 'CT000001')
  select.vm.$emit('change', 'CT000001')
  await flushPromises()
  // 填写全部必填槽位（夹具模板两个 required——任一为空都会触发必填前置拦截）
  const slotInputs = wrapper.findAll('.clause-slot-row input')
  for (const input of slotInputs) {
    await input.setValue('城市餐饮单位经营数据集')
  }
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  mockedCreate.mockReset()
  mockedTemplates.mockReset()
  mockedTemplate.mockReset()
  mockedTemplates.mockResolvedValue(templatePage())
  mockedTemplate.mockResolvedValue(TEMPLATE_DETAIL)
  mockedCreate.mockResolvedValue(ORDER_RESULT)
})

describe('订购弹窗（hifi §4.2）', () => {
  it('打开即拉启用模板列表（pageNum=1/pageSize=50）', async () => {
    await mountDialog()
    expect(mockedTemplates).toHaveBeenCalledWith({ pageNum: 1, pageSize: 50 })
  })

  it('模板未选提交 → 零请求', async () => {
    const wrapper = await mountDialog()
    await wrapper.find('.submit-order-btn').trigger('click')
    await flushPromises()
    expect(mockedCreate).not.toHaveBeenCalled()
  })

  it('选中模板后 getTemplate 拉条款框架；必填槽位未填提交 → 零请求', async () => {
    const wrapper = await mountDialog()
    await selectTemplateAndFillSlot(wrapper)
    // 清空槽位值再提交（必填前置）
    await wrapper.find('.clause-slot-row input').setValue('')
    await wrapper.find('.submit-order-btn').trigger('click')
    await flushPromises()
    expect(mockedTemplate).toHaveBeenCalledWith('CT000001')
    expect(mockedCreate).not.toHaveBeenCalled()
  })

  it('提交成功 emit("ordered", orderNo)', async () => {
    const wrapper = await mountDialog()
    await selectTemplateAndFillSlot(wrapper)
    await wrapper.find('.submit-order-btn').trigger('click')
    await flushPromises()
    expect(mockedCreate).toHaveBeenCalledTimes(1)
    const payload = mockedCreate.mock.calls[0]?.[0]
    expect(payload.productId).toBe(15)
    expect(payload.templateNo).toBe('CT000001')
    expect(payload.requestId).toBeTruthy()
    expect(wrapper.emitted('ordered')?.[0]).toEqual(['OD000001'])
  })

  it('失败（1011C0001）：文案原样展示，重提 requestId 复用，无乐观更新', async () => {
    mockedCreate.mockRejectedValue(
      new ApiError('1011C0001', '该产品已有进行中订单，请勿重复订购'))
    const wrapper = await mountDialog()
    await selectTemplateAndFillSlot(wrapper)
    await wrapper.find('.submit-order-btn').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('该产品已有进行中订单，请勿重复订购')
    expect(wrapper.emitted('ordered')).toBeUndefined()
    // 重提：同 requestId（幂等键复用——hifi §4.2）
    await wrapper.find('.submit-order-btn').trigger('click')
    await flushPromises()
    expect(mockedCreate).toHaveBeenCalledTimes(2)
    expect(mockedCreate.mock.calls[1]?.[0].requestId)
      .toBe(mockedCreate.mock.calls[0]?.[0].requestId)
  })

  it('取消按钮 emit("close")', async () => {
    const wrapper = await mountDialog()
    await wrapper.find('.cancel-order-btn').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
