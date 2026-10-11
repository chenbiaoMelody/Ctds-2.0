import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ClauseEditor from './ClauseEditor.vue'

/**
 * 条款槽位编辑器测试（WBS-3.4.7 hifi §2 + §7 T9）：
 * - 框架模式（发起）：按 clauseFramework.slots 逐行渲染（名称 / 必填标记 / 填写说明 / 值输入），
 *   值输入 → `update:modelValue` 输出 slots 形态 `{键:值}`；
 * - 值模式（提案）：现有槽位**键只读**（键渲染为文本非输入框）+ 值可改 + 新增行（键+值）+ 删行；
 * - 值长度 maxlength 对齐后端口径（≤2000 字符）。
 */
const framework = {
  slots: [
    { key: 'subject_matter', name: '标的', required: true, guide: '授权数据范围的描述' },
    { key: 'delivery_mode', name: '交付方式', required: false, guide: '可选填写' },
  ],
}

interface EditorProps {
  mode: 'framework' | 'values'
  framework?: { slots: { key: string; name: string; required: boolean; guide: string | null }[] }
  modelValue: { slots: Record<string, string> }
}

function mountEditor(props: EditorProps) {
  return mount(ClauseEditor, {
    props,
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

describe('ClauseEditor 框架模式（T9）', () => {
  it('逐槽位渲染：名称 / 必填标记 / 填写说明 / 值输入框', () => {
    const wrapper = mountEditor({ mode: 'framework', framework, modelValue: { slots: {} } })
    const text = wrapper.text()
    expect(text).toContain('标的')
    expect(text).toContain('授权数据范围的描述')
    expect(text).toContain('必填')
    const inputs = wrapper.findAll('input[type="text"], textarea')
    expect(inputs.length).toBeGreaterThanOrEqual(2)
  })

  it('值输入经 update:modelValue 输出 `{slots:{键:值}}` 形态', async () => {
    const wrapper = mountEditor({ mode: 'framework', framework, modelValue: { slots: {} } })
    const inputs = wrapper.findAll('input[type="text"], textarea')
    await inputs[0].setValue('城市餐饮单位经营数据集')
    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const last = emitted!.at(-1)![0] as { slots: Record<string, string> }
    expect(last.slots).toEqual({ subject_matter: '城市餐饮单位经营数据集' })
  })
})

describe('ClauseEditor 值模式（T9）', () => {
  it('现有槽位键只读（文本渲染）+ 值可改 + 输出保留原键', async () => {
    const wrapper = mountEditor({
      mode: 'values',
      modelValue: { slots: { subject_matter: '原值' } },
    })
    expect(wrapper.text()).toContain('subject_matter')
    // 键为只读文本：不存在键输入框（键不可改）
    const keyInputs = wrapper.findAll('input[placeholder*="键"]')
    expect(keyInputs.length).toBe(0)
    const valueInputs = wrapper.findAll('input[type="text"], textarea')
    await valueInputs[0].setValue('改后值')
    const emitted = wrapper.emitted('update:modelValue')
    const last = emitted!.at(-1)![0] as { slots: Record<string, string> }
    expect(last.slots).toEqual({ subject_matter: '改后值' })
  })

  it('新增槽位行（键+值）与删行；非法/空键由服务端判定（不前端硬拦可提交）', async () => {
    const wrapper = mountEditor({
      mode: 'values',
      modelValue: { slots: { subject_matter: '原值' } },
    })
    await wrapper.findAll('button').find((b) => b.text().includes('新增条款项'))!.trigger('click')
    const keyInputs = wrapper.findAll('input[placeholder*="键"]')
    expect(keyInputs.length).toBe(1)
    await keyInputs[0].setValue('extra_item')
    const newValueInput = wrapper.find('input[placeholder="值"]')
    await newValueInput.setValue('新值')
    let emitted = wrapper.emitted('update:modelValue')!
    let last = emitted.at(-1)![0] as { slots: Record<string, string> }
    expect(last.slots).toEqual({ subject_matter: '原值', extra_item: '新值' })
    // 删行（新增行的删除按钮）
    const removeButtons = wrapper.findAll('button').filter((b) => b.text().includes('删除'))
    await removeButtons.at(-1)!.trigger('click')
    emitted = wrapper.emitted('update:modelValue')!
    last = emitted.at(-1)![0] as { slots: Record<string, string> }
    expect(last.slots).toEqual({ subject_matter: '原值' })
  })
})
