import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import RegisterView from './RegisterView.vue'
import { registerSubject } from '../../api/subject'

/**
 * WBS-3.1.5 申请人侧注册页测试：主体类型分支渲染（政务分支提示政务 CA 流程）。
 * WBS-3.1.6 F1 增量：必填缺失逐字段拦截且不发请求（规格行为 1 验收-4 前端侧兑现）、
 * 填齐提交链路（registerSubject 恰一次 + 申请编号提示 + 跳转认证页）。
 */
vi.mock('../../api/subject', () => ({
  registerSubject: vi.fn(),
}))

const mockedRegister = vi.mocked(registerSubject)

const mountPage = async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/subject/certification/:subjectNo', component: { template: '<div />' } },
    ],
  })
  const wrapper = mount(RegisterView, { global: { plugins: [ElementPlus, router] } })
  await flushPromises()
  return { wrapper, router }
}

const fillAllFields = async (wrapper: Awaited<ReturnType<typeof mountPage>>['wrapper']) => {
  const values: Record<string, string> = {
    主体名称: '蓝天数据科技有限公司',
    统一社会信用代码: '91330100MA27XW123X',
    注册地址: '杭州市XX区XX路88号',
    联系人姓名: '李娜',
    联系电话: '13800001234',
    管理员账号: 'admin001',
  }
  for (const item of wrapper.findAll('.el-form-item')) {
    const label = item.find('.el-form-item__label')
    const input = item.find('input.el-input__inner')
    if (label.exists() && input.exists() && values[label.text().replace('：', '').replace(' ', '')]) {
      await input.setValue(values[label.text().replace('：', '').replace(' ', '')])
    }
  }
  await flushPromises()
}

const submitButton = (wrapper: Awaited<ReturnType<typeof mountPage>>['wrapper']) =>
  wrapper.findAll('button').filter((b) => b.text().includes('提交注册'))[0]

describe('主体注册页（WBS-3.1.5 界面化）', () => {
  it('默认企业分支：不出现政务 CA 提示', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.text()).not.toContain('政府部门通道')
  })

  it('选择政府部门后显示政务 CA 流程提示（行为 6 界面分支）', async () => {
    const { wrapper } = await mountPage()
    const radios = wrapper.findAll('.el-radio')
    const govRadio = radios.filter((r) => r.text().includes('政府部门'))[0]
    await govRadio.find('input').setValue()
    await flushPromises()
    expect(wrapper.text()).toContain('政府部门通道')
    expect(wrapper.text()).toContain('政务 CA 数字证书')
  })

  it('注册字段齐全（字段口径 = 3.1.2 已确认契约）', async () => {
    const { wrapper } = await mountPage()
    const text = wrapper.text()
    expect(text).toContain('主体名称')
    expect(text).toContain('统一社会信用代码')
    expect(text).toContain('注册地址')
    expect(text).toContain('联系人姓名')
    expect(text).toContain('联系电话')
    expect(text).toContain('管理员账号')
  })

  it('必填缺失点击提交：逐字段显示原因且不发送注册请求（F1 红锚：删 rules 或 validate 即红）', async () => {
    const { wrapper } = await mountPage()
    await submitButton(wrapper).trigger('click')
    await flushPromises()
    // 错误消息经过渡动画渲染（实测需一个短定时窗口，非微任务级）
    await new Promise((r) => setTimeout(r, 100))
    expect(wrapper.text()).toContain('主体名称不能为空')
    expect(wrapper.text()).toContain('统一社会信用代码不能为空')
    expect(wrapper.text()).toContain('注册地址不能为空')
    expect(wrapper.text()).toContain('联系人姓名不能为空')
    expect(wrapper.text()).toContain('联系电话不能为空')
    expect(wrapper.text()).toContain('管理员账号不能为空')
    expect(mockedRegister).not.toHaveBeenCalled()
  })

  it('填齐提交：恰调用注册接口一次、展示申请编号并跳转认证页（F1 正向链路）', async () => {
    mockedRegister.mockResolvedValue({ subjectNo: 'S20260914000021' })
    const { wrapper, router } = await mountPage()
    await fillAllFields(wrapper)
    await submitButton(wrapper).trigger('click')
    await flushPromises()
    expect(mockedRegister).toHaveBeenCalledTimes(1)
    const payload = mockedRegister.mock.calls[0][0]
    expect(payload.uscc).toBe('91330100MA27XW123X')
    expect(payload.subjectType).toBe('ENTERPRISE')
    expect(document.body.textContent).toContain('注册成功，申请编号：S20260914000021')
    expect(router.currentRoute.value.path).toBe('/subject/certification/S20260914000021')
  })

  // ==== CHG-C-1.1-V1.2：联系电话双格式 + 管理员账号指引（hifi §1.1/§2） ====

  const phoneInput = (wrapper: Awaited<ReturnType<typeof mountPage>>['wrapper']) => {
    for (const item of wrapper.findAll('.el-form-item')) {
      if (item.find('.el-form-item__label').text().includes('联系电话')) {
        return item.find('input.el-input__inner')
      }
    }
    throw new Error('未找到联系电话输入框')
  }

  it('联系电话输入框带双格式指引 placeholder（hifi §1.1 文案定稿）', async () => {
    const { wrapper } = await mountPage()
    expect(phoneInput(wrapper).attributes('placeholder')).toBe('手机 11 位或 区号-座机，如 0571-87654321')
  })

  it('联系电话填非法格式提交：格式红字且不发请求（规格行为 1 第 6 条红锚）', async () => {
    const { wrapper } = await mountPage()
    await fillAllFields(wrapper)
    await phoneInput(wrapper).setValue('测试电话')
    await submitButton(wrapper).trigger('click')
    await flushPromises()
    await new Promise((r) => setTimeout(r, 100))
    expect(wrapper.text()).toContain('联系电话格式不正确（手机 11 位或 区号-座机）')
    expect(mockedRegister).not.toHaveBeenCalled()
  })

  it('联系电话填座机格式提交：通过前端校验发出注册请求（与后端规则同口径）', async () => {
    mockedRegister.mockResolvedValue({ subjectNo: 'S20260919000009' })
    const { wrapper } = await mountPage()
    await fillAllFields(wrapper)
    await phoneInput(wrapper).setValue('0571-87654321')
    await submitButton(wrapper).trigger('click')
    await flushPromises()
    expect(mockedRegister).toHaveBeenCalledTimes(1)
    expect(mockedRegister.mock.calls[0][0].contactPhone).toBe('0571-87654321')
  })

  it('管理员账号输入框下方常驻指引小字（规格行为 1 第 7 条，hifi §1.1 文案定稿）', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain(
      '请填写贵单位自己的平台管理员账号名（仅允许字母、数字与 . _ -），待平台账号体系上线后生效',
    )
  })
})
