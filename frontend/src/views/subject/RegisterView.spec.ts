import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RegisterView from './RegisterView.vue'

/**
 * WBS-3.1.5 申请人侧注册页测试：主体类型分支渲染（政务分支提示政务 CA 流程）。
 * 提交链路由端到端演示覆盖，组件测试聚焦分支与校验。
 */
vi.mock('../../api/subject', () => ({
  registerSubject: vi.fn(),
}))

const mountPage = async () => {
  const wrapper = mount(RegisterView, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  return wrapper
}

describe('主体注册页（WBS-3.1.5 界面化）', () => {
  it('默认企业分支：不出现政务 CA 提示', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).not.toContain('政府部门通道')
  })

  it('选择政府部门后显示政务 CA 流程提示（行为 6 界面分支）', async () => {
    const wrapper = await mountPage()
    const radios = wrapper.findAll('.el-radio')
    const govRadio = radios.filter((r) => r.text().includes('政府部门'))[0]
    await govRadio.find('input').setValue()
    await flushPromises()
    expect(wrapper.text()).toContain('政府部门通道')
    expect(wrapper.text()).toContain('政务 CA 数字证书')
  })

  it('注册字段齐全（字段口径 = 3.1.2 已确认契约）', async () => {
    const wrapper = await mountPage()
    const text = wrapper.text()
    expect(text).toContain('主体名称')
    expect(text).toContain('统一社会信用代码')
    expect(text).toContain('注册地址')
    expect(text).toContain('联系人姓名')
    expect(text).toContain('联系电话')
    expect(text).toContain('管理员账号')
  })
})
