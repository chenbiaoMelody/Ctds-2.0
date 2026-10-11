import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StrategyEditor from './StrategyEditor.vue'

/**
 * 策略编辑器测试（WBS-3.4.7 hifi §2 + §7 T9）：
 * - 五要素开关与取值：次数（开关+整数输入）、期限（开关+起止日期）、用途/域内（开关+文本）、
 *   禁止再分发（开关）+ 显式"无使用限制"勾选；
 * - 输出形态逐字：`{quota:{enabled,maxCount},term:{enabled,startDate,endDate},purpose:{enabled,text},
 *   territory:{enabled,text},noRedistribution:{enabled},noRestrictionDeclared}`；
 * - **非法取值不前端硬拦**（次数负数可提交交服务端 1008C0015 判定——剧本 C-4.3 S1-3 可执行）。
 */
function mountEditor(modelValue: Record<string, unknown> = {}) {
  return mount(StrategyEditor, {
    props: { modelValue },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

function lastEmitted(wrapper: ReturnType<typeof mount>): Record<string, unknown> {
  return wrapper.emitted('update:modelValue')!.at(-1)![0] as Record<string, unknown>
}

describe('StrategyEditor 五要素与输出形态（T9）', () => {
  it('输出对象键固定与后端文档字段名一致（缺省全未启用 + 显式无限制声明布尔）', async () => {
    const wrapper = mountEditor()
    const inputs = wrapper.findAll('input')
    expect(inputs.length).toBeGreaterThan(0)
    const emitted = lastEmitted(wrapper)
    expect(Object.keys(emitted).sort()).toEqual([
      'noRedistribution', 'noRestrictionDeclared', 'purpose', 'quota', 'territory', 'term',
    ].sort())
    expect(emitted.noRestrictionDeclared).toBe(false)
  })

  it('启用次数要素并取值 → quota 形态逐字 {enabled:true,maxCount}', async () => {
    const wrapper = mountEditor()
    // 开关第一组 = 次数（实现顺序固定：quota/term/purpose/territory/noRedistribution）
    const switches = wrapper.findAll('.el-switch')
    await switches[0].trigger('click')
    const numberInput = wrapper.find('input[type="number"], .strategy-quota input')
    if (numberInput.exists()) {
      await numberInput.setValue('100')
    }
    const emitted = lastEmitted(wrapper)
    expect((emitted.quota as Record<string, unknown>).enabled).toBe(true)
    expect((emitted.quota as Record<string, unknown>).maxCount).toBe(100)
  })

  it('非法取值不前端拦截：次数负数原样进入输出（服务端判定为准）', async () => {
    const wrapper = mountEditor()
    const switches = wrapper.findAll('.el-switch')
    await switches[0].trigger('click')
    const numberInput = wrapper.find('input[type="number"], .strategy-quota input')
    await numberInput.setValue('-1')
    const emitted = lastEmitted(wrapper)
    expect((emitted.quota as Record<string, unknown>).maxCount).toBe(-1)
  })

  it('勾选"无使用限制"→ noRestrictionDeclared:true（互斥由服务端判定）', async () => {
    const wrapper = mountEditor()
    const checkbox = wrapper.findAll('.el-checkbox').find((c) =>
      c.text().includes('无使用限制'))
    expect(checkbox).toBeTruthy()
    await checkbox!.trigger('click')
    expect(lastEmitted(wrapper).noRestrictionDeclared).toBe(true)
  })

  it('启用期限要素 → term 形态逐字 {enabled,startDate,endDate}', async () => {
    const wrapper = mountEditor()
    const switches = wrapper.findAll('.el-switch')
    await switches[1].trigger('click')
    const emitted = lastEmitted(wrapper)
    const term = emitted.term as Record<string, unknown>
    expect(term.enabled).toBe(true)
    expect(Object.keys(term)).toEqual(expect.arrayContaining(['startDate', 'endDate']))
  })

  it('传入初值回显：modelValue 各要素映射到本地状态（提案弹窗不丢当前策略——评审③ P1）', () => {
    const wrapper = mountEditor({
      quota: { enabled: true, maxCount: 100 },
      term: { enabled: true, startDate: '2026-10-08', endDate: '2026-11-07' },
      purpose: { enabled: true, text: '风控建模' },
      territory: { enabled: true, text: '本市域' },
      noRedistribution: { enabled: true },
      noRestrictionDeclared: true,
    })
    const emitted = lastEmitted(wrapper)
    expect((emitted.quota as Record<string, unknown>).enabled).toBe(true)
    expect((emitted.quota as Record<string, unknown>).maxCount).toBe(100)
    expect((emitted.term as Record<string, unknown>).startDate).toBe('2026-10-08')
    expect((emitted.term as Record<string, unknown>).endDate).toBe('2026-11-07')
    expect((emitted.purpose as Record<string, unknown>).text).toBe('风控建模')
    expect((emitted.territory as Record<string, unknown>).text).toBe('本市域')
    expect((emitted.noRedistribution as Record<string, unknown>).enabled).toBe(true)
    expect(emitted.noRestrictionDeclared).toBe(true)
  })
})
