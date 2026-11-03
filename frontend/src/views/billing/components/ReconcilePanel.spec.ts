import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ReconcilePanel from './ReconcilePanel.vue'
import { listReconciliations, runReconciliation, type Reconciliation } from '../../../api/billing'
import { setDemoSubject } from '../../../api/client'
import { setActorMode } from '../../../stores/demoIdentity'

/**
 * 对账工作台测试（WBS-3.7.7 hifi §3 P6；§9 T6/T7）：
 * - T6 正向：PASS → 结论「通过」+ 八项数值 + 差异空态；DIFFERENCE → 结论「差异」+ 七类差异逐条 + 冻结提示；
 * - T6 负向：空值前端拦截零请求；C0009/C0014 服务端文案原样展示；
 * - T7 记录列表：分页参数、结论标签、点击记录回填结论卡。
 */
vi.mock('../../../api/billing', () => ({
  listReconciliations: vi.fn(),
  runReconciliation: vi.fn(),
}))

const mockedRun = vi.mocked(runReconciliation)
const mockedRecords = vi.mocked(listReconciliations)

function reconciliation(over: Partial<Reconciliation> = {}): Reconciliation {
  return {
    id: 1,
    contractNo: 'CO000001',
    period: '2026-10',
    counterUsedCount: 10,
    usageLogAllowedCount: 10,
    meteringFlowCount: 10,
    usageLogAllowedAllTime: 12,
    meteringFlowAllTime: 12,
    billQuantity: 10,
    billTotalAmount: '30.00',
    recomputedAmount: '30.00',
    conclusion: 'PASS',
    differences: [],
    createdBy: 'S20260925000001',
    createdAt: '2026-11-01T00:30:00',
    ...over,
  }
}

function page<T>(list: T[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPanel() {
  const wrapper = mount(ReconcilePanel, {
    attachTo: document.body,
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

/** 账期走 el-date-picker（hifi P6 月份选择）：以组件事件驱动 v-model。 */
async function setPeriod(wrapper: ReturnType<typeof mount>, value: string) {
  wrapper.findComponent({ name: 'ElDatePicker' }).vm.$emit('update:modelValue', value)
  await flushPromises()
}

async function fillAndRun(wrapper: ReturnType<typeof mount>, contractNo = 'CO000001') {
  await wrapper.find('.reconcile-contract input').setValue(contractNo)
  await setPeriod(wrapper, '2026-10')
  await wrapper.find('.run-reconcile-btn').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.clearAllMocks()
  setDemoSubject('S20260925000001')
  setActorMode('operator')
  mockedRecords.mockResolvedValue(page([reconciliation()]))
})

describe('T6 执行对账（hifi §3 P6）', () => {
  it('执行：合约号 + 账期透传', async () => {
    mockedRun.mockResolvedValue(reconciliation())
    const wrapper = await mountPanel()
    await fillAndRun(wrapper)
    expect(mockedRun).toHaveBeenCalledWith('CO000001', '2026-10')
  })

  it('PASS：结论「通过」+ 八项数值齐备', async () => {
    mockedRun.mockResolvedValue(reconciliation())
    const wrapper = await mountPanel()
    await fillAndRun(wrapper)
    expect(wrapper.find('.conclusion-tag').text()).toBe('通过')
    const figures = wrapper.find('.figures-table').text()
    expect(figures).toContain('合约计数（余额口径）')
    expect(figures).toContain('执行流水放行数（本期）')
    expect(figures).toContain('执行流水放行数（全量）')
    expect(figures).toContain('计量流水数（本期）')
    expect(figures).toContain('计量流水数（全量）')
    expect(figures).toContain('按次账单数量')
    expect(figures).toContain('账单合计金额')
    expect(figures).toContain('按快照复算金额')
    expect(figures).toContain('30.00')
  })

  it('PASS：差异表空态「无差异」，无冻结提示', async () => {
    mockedRun.mockResolvedValue(reconciliation())
    const wrapper = await mountPanel()
    await fillAndRun(wrapper)
    expect(wrapper.find('.differences-table').text()).toContain('无差异')
    expect(wrapper.find('.difference-alert').exists()).toBe(false)
  })

  it('DIFFERENCE：结论「差异」+ 七类差异逐条（type 中文 / leg / expected / actual / detail）', async () => {
    mockedRun.mockResolvedValue(reconciliation({
      conclusion: 'DIFFERENCE',
      differences: [
        {
          type: 'COUNTER_VS_USAGE_LOG_ALL',
          leg: '余额计数 vs 执行流水',
          expected: '12',
          actual: '10',
          detail: '合约余额计数与全量执行流水不一致',
        },
        {
          type: 'AMOUNT_RECOMPUTE',
          leg: '账单金额复算',
          expected: '30.00',
          actual: '32.00',
          detail: '按快照复算金额与账单合计不一致',
        },
      ],
    }))
    const wrapper = await mountPanel()
    await fillAndRun(wrapper)
    expect(wrapper.find('.conclusion-tag').text()).toBe('差异')
    const differences = wrapper.find('.differences-table').text()
    expect(differences).toContain('合约余额计数 vs 全量执行流水')
    expect(differences).toContain('12')
    expect(differences).toContain('10')
    expect(differences).toContain('账单金额复算')
    expect(differences).toContain('按快照复算金额与账单合计不一致')
  })

  it('DIFFERENCE：顶部冻结提示条（fail-closed 口径）', async () => {
    mockedRun.mockResolvedValue(reconciliation({ conclusion: 'DIFFERENCE' }))
    const wrapper = await mountPanel()
    await fillAndRun(wrapper)
    expect(wrapper.find('.difference-alert').text())
      .toContain('该合约该账期账单已冻结，确认前需澄清差异并重新对账通过')
  })

  it('未知差异类型：原值原样显示（§7 不隐藏不猜测）', async () => {
    mockedRun.mockResolvedValue(reconciliation({
      conclusion: 'DIFFERENCE',
      differences: [{
        type: 'NEW_UNKNOWN_TYPE', leg: 'x', expected: '1', actual: '2', detail: 'd',
      }],
    }))
    const wrapper = await mountPanel()
    await fillAndRun(wrapper)
    expect(wrapper.find('.differences-table').text()).toContain('NEW_UNKNOWN_TYPE')
  })

  it('空合约号：前端拦截（零请求）', async () => {
    const wrapper = await mountPanel()
    await setPeriod(wrapper, '2026-10')
    await wrapper.find('.run-reconcile-btn').trigger('click')
    await flushPromises()
    expect(mockedRun).not.toHaveBeenCalled()
    expect(wrapper.find('.reconcile-error').text()).toContain('请填写合约号')
  })

  it('空账期：前端拦截（零请求）', async () => {
    const wrapper = await mountPanel()
    await wrapper.find('.reconcile-contract input').setValue('CO000001')
    await wrapper.find('.run-reconcile-btn').trigger('click')
    await flushPromises()
    expect(mockedRun).not.toHaveBeenCalled()
    expect(wrapper.find('.reconcile-error').text()).toContain('请选择账期')
  })

  it('1011C0009 无该期账单：原样展示服务端文案', async () => {
    mockedRun.mockRejectedValue(new Error('该合约该账期无账单可对账'))
    mockedRecords.mockResolvedValue(page([]))
    const wrapper = await mountPanel()
    await fillAndRun(wrapper)
    expect(wrapper.find('.reconcile-error').text()).toContain('该合约该账期无账单可对账')
  })

  it('1011C0014 合约不存在：原样展示服务端文案', async () => {
    mockedRun.mockRejectedValue(new Error('合约不存在或不可读'))
    mockedRecords.mockResolvedValue(page([]))
    const wrapper = await mountPanel()
    await fillAndRun(wrapper, 'CO999999')
    expect(wrapper.find('.reconcile-error').text()).toContain('合约不存在或不可读')
  })

  it('「查看该期账单」：向容器抛出切换事件并携带合约与账期', async () => {
    mockedRun.mockResolvedValue(reconciliation())
    const wrapper = await mountPanel()
    await fillAndRun(wrapper)
    await wrapper.find('.view-bills-btn').trigger('click')
    const emitted = wrapper.emitted('view-bills')
    expect(emitted).toBeTruthy()
    expect(emitted?.[0]).toEqual([{ contractNo: 'CO000001', period: '2026-10' }])
  })
})

describe('T7 对账记录列表（hifi §3 P6）', () => {
  it('执行后拉取记录：渲染执行时点 / 结论 / 差异数 / 执行人', async () => {
    mockedRun.mockResolvedValue(reconciliation())
    mockedRecords.mockResolvedValue(page([
      reconciliation(),
      reconciliation({ id: 2, conclusion: 'DIFFERENCE', differences: [
        { type: 'AMOUNT_RECOMPUTE', leg: 'x', expected: '1', actual: '2', detail: 'd' },
      ] }),
    ]))
    const wrapper = await mountPanel()
    await fillAndRun(wrapper)
    const records = wrapper.find('.record-table').text()
    expect(records).toContain('2026-11-01T00:30:00')
    expect(records).toContain('通过')
    expect(records).toContain('差异')
    expect(records).toContain('S20260925000001')
    expect(mockedRecords).toHaveBeenCalledWith('CO000001', expect.objectContaining({
      pageNum: 1, pageSize: 10, period: '2026-10',
    }))
  })

  it('分页：切换页码后以新页码重新请求', async () => {
    mockedRun.mockResolvedValue(reconciliation())
    mockedRecords.mockResolvedValue({ list: [], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    const wrapper = await mountPanel()
    await fillAndRun(wrapper)
    const nextBtn = wrapper.find('.record-pagination .btn-next')
    expect(nextBtn.exists()).toBe(true)
    await nextBtn.trigger('click')
    await flushPromises()
    expect(mockedRecords).toHaveBeenLastCalledWith('CO000001', expect.objectContaining({ pageNum: 2 }))
  })

  it('点击记录行：结论卡回填该次记录', async () => {
    mockedRun.mockResolvedValue(reconciliation())
    mockedRecords.mockResolvedValue(page([
      reconciliation({ id: 7, conclusion: 'DIFFERENCE', createdBy: 'S20260925000009',
        differences: [{ type: 'AMOUNT_RECOMPUTE', leg: 'x', expected: '1', actual: '2', detail: 'd' }] }),
    ]))
    const wrapper = await mountPanel()
    await fillAndRun(wrapper)
    await wrapper.find('.record-row').trigger('click')
    await flushPromises()
    expect(wrapper.find('.conclusion-tag').text()).toBe('差异')
    expect(wrapper.find('.conclusion-card').text()).toContain('S20260925000009')
  })
})
