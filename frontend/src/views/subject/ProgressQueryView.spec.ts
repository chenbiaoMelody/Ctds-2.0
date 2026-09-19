import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import ProgressQueryView from './ProgressQueryView.vue'
import { fetchRegistrationProgress } from '../../api/subject'
import { ApiError } from '../../api/client'

/**
 * CHG-C-1.1-V1.2 入驻进度查询页测试（规格行为 8 + hifi §1.2/§4 编码契约）：
 * 页面渲染、空输入拦截不发请求、双凭证匹配结果卡、已驳回理由提示、
 * 统一失败文案防枚举、前往认证页跳转、信用代码位数不足前端红字。
 */
vi.mock('../../api/subject', () => ({
  fetchRegistrationProgress: vi.fn(),
}))

const mockedFetch = vi.mocked(fetchRegistrationProgress)

const mountPage = async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/subject/progress', component: ProgressQueryView },
      { path: '/subject/certification/:subjectNo', component: { template: '<div />' } },
    ],
  })
  const wrapper = mount(ProgressQueryView, { global: { plugins: [ElementPlus, router] } })
  await flushPromises()
  return { wrapper, router }
}

const queryButton = (wrapper: Awaited<ReturnType<typeof mountPage>>['wrapper']) =>
  wrapper.findAll('button').filter((b) => b.text().includes('查询'))[0]

const clickQuery = async (wrapper: Awaited<ReturnType<typeof mountPage>>['wrapper']) => {
  await queryButton(wrapper).trigger('click')
  await flushPromises()
  // 错误消息经过渡动画渲染（与 RegisterView.spec 同口径，需一个短定时窗口）
  await new Promise((r) => setTimeout(r, 100))
}

const fillAndQuery = async (
  wrapper: Awaited<ReturnType<typeof mountPage>>['wrapper'],
  subjectNo: string,
  uscc: string,
) => {
  const inputs = wrapper.findAll('input.el-input__inner')
  await inputs[0].setValue(subjectNo)
  await inputs[1].setValue(uscc)
  await clickQuery(wrapper)
}

describe('入驻进度查询页（CHG-C-1.1-V1.2 行为 8）', () => {
  it('页面渲染：标题、说明小字、双凭证输入框与查询按钮（hifi §1.2）', async () => {
    const { wrapper } = await mountPage()
    const text = wrapper.text()
    expect(text).toContain('入驻进度查询')
    expect(text).toContain('凭注册时返回的申请编号与本主体统一社会信用代码查询入驻进度')
    expect(wrapper.findAll('input.el-input__inner').length).toBe(2)
    expect(queryButton(wrapper).exists()).toBe(true)
  })

  it('申请编号为空点击查询：必填红字且不发请求', async () => {
    const { wrapper } = await mountPage()
    const inputs = wrapper.findAll('input.el-input__inner')
    await inputs[1].setValue('91330100MA27XW123X')
    await clickQuery(wrapper)
    expect(wrapper.text()).toContain('申请编号不能为空')
    expect(mockedFetch).not.toHaveBeenCalled()
  })

  it('信用代码为空点击查询：必填红字且不发请求', async () => {
    const { wrapper } = await mountPage()
    const inputs = wrapper.findAll('input.el-input__inner')
    await inputs[0].setValue('S20260919000001')
    await clickQuery(wrapper)
    expect(wrapper.text()).toContain('统一社会信用代码不能为空')
    expect(mockedFetch).not.toHaveBeenCalled()
  })

  it('信用代码位数不足点击查询：格式红字且不发请求（hifi §4：前端格式红字优先）', async () => {
    const { wrapper } = await mountPage()
    await fillAndQuery(wrapper, 'S20260919000001', '91330100MA27XW12')
    expect(wrapper.text()).toContain('统一社会信用代码格式不正确')
    expect(mockedFetch).not.toHaveBeenCalled()
  })

  it('双凭证匹配：结果卡展示主体名称/类型中文/状态徽标且无驳回提示（行为 8-1）', async () => {
    mockedFetch.mockResolvedValue({
      subjectNo: 'S20260919000001',
      subjectName: '蓝天数据科技有限公司',
      subjectType: 'ENTERPRISE',
      status: 'ADMITTED',
      rejectReason: null,
    })
    const { wrapper } = await mountPage()
    await fillAndQuery(wrapper, 'S20260919000001', '91330100MA27XW123X')
    expect(mockedFetch).toHaveBeenCalledTimes(1)
    expect(mockedFetch.mock.calls[0]).toEqual(['S20260919000001', '91330100MA27XW123X'])
    expect(wrapper.text()).toContain('蓝天数据科技有限公司')
    expect(wrapper.text()).toContain('企业')
    expect(wrapper.text()).toContain('已入驻')
    expect(wrapper.text()).not.toContain('驳回理由')
  })

  it('已驳回主体：结果卡红色提示展示驳回理由（行为 8-2，剧本 S2-7）', async () => {
    mockedFetch.mockResolvedValue({
      subjectNo: 'S20260919000002',
      subjectName: '驳回演示公司',
      subjectType: 'INSTITUTION',
      status: 'REJECTED',
      rejectReason: '材料不齐全，予以驳回',
    })
    const { wrapper } = await mountPage()
    await fillAndQuery(wrapper, 'S20260919000002', '91330100MA27XW123X')
    expect(wrapper.text()).toContain('驳回理由：材料不齐全，予以驳回')
  })

  it('查询失败（编号不存在或凭证不符）：页面顶部统一文案（行为 8-3 防枚举）', async () => {
    mockedFetch.mockRejectedValue(new ApiError('1000C0003', '未查询到匹配的申请'))
    const { wrapper } = await mountPage()
    await fillAndQuery(wrapper, 'S20260919999999', '91330100MA27XW123X')
    expect(wrapper.text()).toContain('未查询到匹配的申请')
  })

  it('结果卡"前往认证页"按钮跳转认证页（行为 8-5）', async () => {
    mockedFetch.mockResolvedValue({
      subjectNo: 'S20260919000003',
      subjectName: '跳转演示公司',
      subjectType: 'GOV',
      status: 'PENDING_CERT',
      rejectReason: null,
    })
    const { wrapper, router } = await mountPage()
    await fillAndQuery(wrapper, 'S20260919000003', '91330100MA27XW123X')
    const button = wrapper.findAll('button').filter((b) => b.text().includes('前往认证页'))[0]
    await button.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/subject/certification/S20260919000003')
  })
})
