import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, DOMWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import TemplateView from './TemplateView.vue'
import {
  listTemplates,
  getTemplate,
  listManageTemplates,
  listTemplateVersions,
  listTemplateActionLogs,
  createTemplate,
  reviseTemplate,
  disableTemplate,
  enableTemplate,
  type ContractTemplateItem,
  type TemplateDetail,
  type ManageTemplate,
  type TemplateVersionItem,
  type TemplateActionLogItem,
} from '../../api/contract'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'
import {
  TEMPLATE_NOT_ACCESSIBLE_TIP,
  TEMPLATE_MAINTAIN_FORBIDDEN_TIP,
  ADMISSION_REQUIRED_BROWSE_TIP,
} from '../../constants/contract'

/**
 * 合约模板页测试（WBS-3.4.7 hifi §6.2 + §7 T3/T4/T5/T6）：
 * - T3 浏览：类型过滤传参 / 列表渲染（仅启用中）/ 详情抽屉条款框架表（名称/必填/说明）/
 *   空态 / 分页传参 / 停用·不存在分支共用同一提示常量（反向探针：改文案必红——源集守卫锚定）；
 * - T4 未入驻：1008C0003 浏览语境统一文案原样展示（不出现差异表述）；
 * - T5 运营：R1 列表（含停用）/ W1 新增（名称/类型/框架槽位行）+ 同名 C0005 原样 /
 *   W2 修订（新版本号提示）+ R2 版本历史旧版本可查 / W3·W4 停用启用 + 同态 C0007 原样 /
 *   普通档运营区隐藏（体验层）；维护被拒（C0002）原样；
 * - T6 维护留痕（R3）：DENIED_MANAGE 拒绝行渲染 + 空态 + 翻页。
 */
vi.mock('../../api/contract', () => ({
  listTemplates: vi.fn(),
  getTemplate: vi.fn(),
  listManageTemplates: vi.fn(),
  listTemplateVersions: vi.fn(),
  listTemplateActionLogs: vi.fn(),
  createTemplate: vi.fn(),
  reviseTemplate: vi.fn(),
  disableTemplate: vi.fn(),
  enableTemplate: vi.fn(),
}))

const mockedTemplates = vi.mocked(listTemplates)
const mockedTemplate = vi.mocked(getTemplate)
const mockedManage = vi.mocked(listManageTemplates)
const mockedVersions = vi.mocked(listTemplateVersions)
const mockedLogs = vi.mocked(listTemplateActionLogs)
const mockedCreate = vi.mocked(createTemplate)
const mockedRevise = vi.mocked(reviseTemplate)
const mockedDisable = vi.mocked(disableTemplate)
const mockedEnable = vi.mocked(enableTemplate)

function template(over: Partial<ContractTemplateItem> = {}): ContractTemplateItem {
  return {
    templateNo: 'CT000001',
    name: '公共数据授权合约模板',
    type: { code: 'PUBLIC_DATA_AUTHORIZATION', displayName: '公共数据授权' },
    currentVersion: 1,
    ...over,
  }
}

function templateDetail(over: Partial<TemplateDetail> = {}): TemplateDetail {
  return {
    templateNo: 'CT000001',
    name: '公共数据授权合约模板',
    type: { code: 'PUBLIC_DATA_AUTHORIZATION', displayName: '公共数据授权' },
    currentVersion: 1,
      clauseFramework: {
        slots: [
          { key: 'subject_matter', name: '标的', required: true, guide: '授权数据范围的描述' },
          { key: 'delivery_mode', name: '交付方式', required: false, guide: '自由填写' },
        ],
      },
    publishedAt: '2026-10-05T21:44:11',
    ...over,
  }
}

function manageTemplate(over: Partial<ManageTemplate> = {}): ManageTemplate {
  return {
    templateNo: 'CT000002',
    name: 'API调用服务合约模板',
    type: { code: 'API_CALL', displayName: 'API调用' },
    currentVersion: 2,
    status: 'ENABLED',
    createdBy: 'platform-seed',
    createdAt: '2026-10-05T21:44:11',
    updatedAt: '2026-10-05T21:46:14',
    ...over,
  }
}

function version(over: Partial<TemplateVersionItem> = {}): TemplateVersionItem {
  return {
    versionNo: 1,
    publishedBy: 'platform-seed',
    publishedAt: '2026-10-05T21:44:11',
    clauseFramework: templateDetail().clauseFramework,
    ...over,
  }
}

function tplLog(over: Partial<TemplateActionLogItem> = {}): TemplateActionLogItem {
  return {
    id: 6,
    templateNo: 'CT000001',
    versionNo: 0,
    action: 'DENIED_MANAGE',
    actorSubjectNo: 'S20260915000001',
    reasonCode: 'C0003',
    fromValue: null,
    toValue: null,
    createdAt: '2026-10-05T22:00:00',
    ...over,
  }
}

function page<T>(list: T[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 10, totalPages: 1 }
}

// mountPage 所建 router 的模块级引用（认证失效引导用例断言跳转用；mountPage 返回值不变）
let currentRouter: Router | undefined

async function mountPage(route = '/contracts/templates') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/contracts/templates', name: 'contract-templates', component: TemplateView },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  currentRouter = router
  await router.push(route)
  await router.isReady()
  const wrapper = mount(TemplateView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return wrapper
}

async function clickBodyButton(text: string): Promise<void> {
  const btn = [...document.body.querySelectorAll('button')]
    .filter((b) => b.textContent?.includes(text)).at(-1)
  expect(btn).toBeTruthy()
  await new DOMWrapper(btn as HTMLElement).trigger('click')
}

function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((b) => b.text().includes(text))
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  for (const fn of [mockedTemplates, mockedTemplate, mockedManage, mockedVersions, mockedLogs,
    mockedCreate, mockedRevise, mockedDisable, mockedEnable]) {
    fn.mockReset()
  }
  setDemoSubject('S20260925000001')
  setActorMode('subject')
  mockedTemplates.mockResolvedValue(page([template()]))
})

describe('模板浏览（T3）', () => {
  it('列表渲染模板三要素并只来自 R4（启用中由服务端保障，界面不造过滤）；类型列独立渲染', async () => {
    // 名称不含类型字样——"公共数据授权"只能来自类型列 displayName（防子串垫绿——评审④ P3）
    mockedTemplates.mockResolvedValue(page([template({ name: '城市数据集模板' })]))
    await mountPage()
    expect(document.body.textContent).toContain('CT000001')
    expect(document.body.textContent).toContain('城市数据集模板')
    expect(document.body.textContent).toContain('公共数据授权')
    expect(mockedTemplates).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 10 }))
  })

  it('类型过滤传参：选择类型后点击查询按 type 传参，重置清空', async () => {
    const wrapper = await mountPage()
    mockedTemplates.mockClear()
    const select = wrapper.find('.filter-bar .el-select')
    if (select.exists()) {
      await select.trigger('click')
      await flushPromises()
      const option = [...document.body.querySelectorAll('.el-select-dropdown__item')]
        .find((o) => o.textContent?.includes('API调用'))
      expect(option).toBeTruthy()
      await new DOMWrapper(option as HTMLElement).trigger('click')
      await flushPromises()
      // 查询按钮触发（hifi §6.2-A：过滤条 +「查询」「重置」；选择不自动查询——评审① P3）
      await button(wrapper, '查询')!.trigger('click')
      await flushPromises()
      expect(mockedTemplates).toHaveBeenCalledWith(expect.objectContaining({ type: 'API_CALL' }))
      await button(wrapper, '重置')!.trigger('click')
      await flushPromises()
      expect(mockedTemplates).toHaveBeenLastCalledWith(expect.objectContaining({ type: undefined }))
    } else {
      // 类型过滤控件缺失 = 界面承载缺失（判定面不可执行）
      expect.fail('模板浏览区必须提供类型过滤控件')
    }
  })

  it('详情抽屉渲染条款框架表：槽位名称 / 必填标记 / 填写说明', async () => {
    mockedTemplate.mockResolvedValue(templateDetail())
    const wrapper = await mountPage()
    await button(wrapper, '查看详情')!.trigger('click')
    await flushPromises()
    expect(mockedTemplate).toHaveBeenCalledWith('CT000001')
    const text = document.body.textContent
    expect(text).toContain('标的')
    expect(text).toContain('必填')
    expect(text).toContain('授权数据范围的描述')
    expect(text).toContain('可选')
  })

  it('详情不可达（停用/不存在）→ 同一条提示常量原样展示（不区分差异）', async () => {
    mockedTemplate.mockRejectedValue(new ApiError('1008C0001', TEMPLATE_NOT_ACCESSIBLE_TIP))
    const wrapper = await mountPage()
    await button(wrapper, '查看详情')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(TEMPLATE_NOT_ACCESSIBLE_TIP)
  })

  it('空态"暂无启用中的模板"（空态文案非报错）', async () => {
    mockedTemplates.mockResolvedValue(page([]))
    await mountPage()
    expect(document.body.textContent).toContain('暂无启用中的模板')
  })
})

describe('未入驻主体（T4）', () => {
  it('浏览语境 1008C0003 统一文案原样展示（不出现差异表述）', async () => {
    mockedTemplates.mockRejectedValue(
      new ApiError('1008C0003', ADMISSION_REQUIRED_BROWSE_TIP))
    await mountPage()
    expect(document.body.textContent).toContain('主体未入驻或不存在，无法浏览模板')
    // 同形口径：不得出现改写版（"主体不存在"单独差异表述）
    expect(document.body.textContent).not.toContain('主体不存在，无法')
  })
})

describe('模板运营（T5）', () => {
  it('普通档：运营区页签隐藏（体验层；服务端判定为准）', async () => {
    setActorMode('subject')
    await mountPage()
    expect(document.body.textContent).not.toContain('运营维护')
  })

  it('运营档：R1 列表渲染（含停用状态）与新增入口可见', async () => {
    setActorMode('operator')
    mockedManage.mockResolvedValue(page([manageTemplate(), manageTemplate({
      templateNo: 'CT000009', status: 'DISABLED', name: '已停用模板',
    })]))
    const wrapper = await mountPage()
    await button(wrapper, '运营维护')!.trigger('click')
    await flushPromises()
    expect(mockedManage).toHaveBeenCalled()
    expect(document.body.textContent).toContain('CT000002')
    expect(document.body.textContent).toContain('已停用')
    expect(button(wrapper, '新增模板')).toBeTruthy()
  })

  it('运营区状态/类型可选过滤传参（hifi §6.2-E——评审① P2）', async () => {
    setActorMode('operator')
    mockedManage.mockResolvedValue(page([manageTemplate()]))
    const wrapper = await mountPage()
    await button(wrapper, '运营维护')!.trigger('click')
    await flushPromises()
    mockedManage.mockClear()
    const select = wrapper.find('.filter-bar .el-select')
    expect(select.exists(), '运营区必须提供状态/类型过滤控件').toBe(true)
    await select.trigger('click')
    await flushPromises()
    const option = [...document.body.querySelectorAll('.el-select-dropdown__item')]
      .find((o) => o.textContent?.includes('已停用'))
    expect(option).toBeTruthy()
    await new DOMWrapper(option as HTMLElement).trigger('click')
    await flushPromises()
    await button(wrapper, '查询')!.trigger('click')
    await flushPromises()
    expect(mockedManage).toHaveBeenCalledWith(expect.objectContaining({ status: 'DISABLED' }))
    await button(wrapper, '重置')!.trigger('click')
    await flushPromises()
    expect(mockedManage).toHaveBeenLastCalledWith(expect.objectContaining({ status: undefined }))
    // 类型过滤传参（复审① P3 补钉：type 与 status 双传参各自可达——类型为第二个下拉）
    mockedManage.mockClear()
    const selects = wrapper.findAll('.filter-bar .el-select')
    expect(selects.length).toBeGreaterThanOrEqual(2)
    await selects[1].trigger('click')
    await flushPromises()
    const typeOption = [...document.body.querySelectorAll('.el-select-dropdown__item')]
      .find((o) => o.textContent?.includes('API调用'))
    expect(typeOption).toBeTruthy()
    await new DOMWrapper(typeOption as HTMLElement).trigger('click')
    await flushPromises()
    await button(wrapper, '查询')!.trigger('click')
    await flushPromises()
    expect(mockedManage).toHaveBeenCalledWith(expect.objectContaining({ type: 'API_CALL' }))
  })

  it('运营档普通主体发维护请求被拒 → C0002 原样展示', async () => {
    setActorMode('operator')
    mockedManage.mockRejectedValue(new ApiError('1008C0002', TEMPLATE_MAINTAIN_FORBIDDEN_TIP))
    const wrapper = await mountPage()
    await button(wrapper, '运营维护')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(TEMPLATE_MAINTAIN_FORBIDDEN_TIP)
  })

  it('W1 新增模板：提交契约逐字（name/type/clauseFramework.slots）+ 成功提示', async () => {
    setActorMode('operator')
    mockedManage.mockResolvedValue(page([manageTemplate()]))
    mockedCreate.mockResolvedValue({ templateNo: 'CT000010', versionNo: 1 })
    const wrapper = await mountPage()
    await button(wrapper, '运营维护')!.trigger('click')
    await flushPromises()
    await button(wrapper, '新增模板')!.trigger('click')
    await flushPromises()
    const nameInput = wrapper.find('input[placeholder*="名称"], .template-form input')
    expect(nameInput.exists()).toBe(true)
    await nameInput.setValue('测试模板')
    // 槽位行编辑器：默认空框架前置拦截（零请求），补一行后可提交
    await button(wrapper, '确定')!.trigger('click')
    await flushPromises()
    expect(mockedCreate).not.toHaveBeenCalled()
    await button(wrapper, '新增条款项')!.trigger('click')
    await flushPromises()
    const keyInputs = wrapper.findAll('input[placeholder*="键"], input[placeholder*="英文标识"]')
    expect(keyInputs.length).toBeGreaterThan(0)
    await keyInputs[0].setValue('usage_scope')
    const nameInputs = wrapper.findAll('.clause-slot-row input')
    if (nameInputs.length > 1) {
      await nameInputs[1].setValue('使用范围')
    }
    await button(wrapper, '确定')!.trigger('click')
    await flushPromises()
    expect(mockedCreate).toHaveBeenCalledWith(expect.objectContaining({
      name: '测试模板',
      type: 'PUBLIC_DATA_AUTHORIZATION',
      clauseFramework: expect.objectContaining({
        slots: expect.arrayContaining([expect.objectContaining({ key: 'usage_scope' })]),
      }),
    }))
  })

  it('W3 停用 / W4 启用传参与同态重复被拒 C0007 原样', async () => {
    setActorMode('operator')
    mockedManage.mockResolvedValue(page([manageTemplate()]))
    mockedDisable.mockResolvedValue({ templateNo: 'CT000002', versionNo: 2, status: 'DISABLED' })
    const wrapper = await mountPage()
    await button(wrapper, '运营维护')!.trigger('click')
    await flushPromises()
    await button(wrapper, '停用')!.trigger('click')
    await flushPromises()
    await flushPromises()
    await clickBodyButton('确定')
    await flushPromises()
    expect(mockedDisable).toHaveBeenCalledWith('CT000002')
    mockedDisable.mockRejectedValue(new ApiError('1008C0007', '模板已处于目标状态'))
    await button(wrapper, '停用')!.trigger('click')
    await flushPromises()
    await flushPromises()
    await clickBodyButton('确定')
    await flushPromises()
    expect(document.body.textContent).toContain('模板已处于目标状态')
  })

  it('W4 启用腿：DISABLED 模板启用传参 + 启用方向同态 C0007 原样（评审④ P2 补测）', async () => {
    setActorMode('operator')
    mockedManage.mockResolvedValue(page([manageTemplate({
      templateNo: 'CT000009', status: 'DISABLED', name: '已停用模板',
    })]))
    mockedEnable.mockResolvedValue({ templateNo: 'CT000009', versionNo: 2, status: 'ENABLED' })
    const wrapper = await mountPage()
    await button(wrapper, '运营维护')!.trigger('click')
    await flushPromises()
    await button(wrapper, '启用')!.trigger('click')
    await flushPromises()
    await flushPromises()
    await clickBodyButton('确定')
    await flushPromises()
    expect(mockedEnable).toHaveBeenCalledWith('CT000009')
    mockedEnable.mockRejectedValue(new ApiError('1008C0007', '模板已处于目标状态'))
    await button(wrapper, '启用')!.trigger('click')
    await flushPromises()
    await flushPromises()
    await clickBodyButton('确定')
    await flushPromises()
    expect(document.body.textContent).toContain('模板已处于目标状态')
  })

  it('W2 修订预载现有条款框架（不丢既有槽位）+ 新版本号提示全锚；R2 版本历史旧版本可查', async () => {
    setActorMode('operator')
    mockedManage.mockResolvedValue(page([manageTemplate()]))
    mockedTemplate.mockResolvedValue(templateDetail({ templateNo: 'CT000002' }))
    mockedRevise.mockResolvedValue({ templateNo: 'CT000002', versionNo: 3, previousVersionNo: 2 })
    const wrapper = await mountPage()
    await button(wrapper, '运营维护')!.trigger('click')
    await flushPromises()
    await button(wrapper, '修订')!.trigger('click')
    await flushPromises()
    // 预载现有框架：编辑器呈现既有两槽位（键已回填，不空开弹窗）
    expect(mockedTemplate).toHaveBeenCalledWith('CT000002')
    const existedKeys = wrapper.findAll('input[placeholder*="键"], input[placeholder*="英文标识"]')
      .map((i) => (i.element as HTMLInputElement).value)
    expect(existedKeys).toContain('subject_matter')
    expect(existedKeys).toContain('delivery_mode')
    // 追加一个新条款项后提交：既有槽位不得被静默清空（P1 回归锚：slots = 既有 2 + 新增 1）
    await button(wrapper, '新增条款项')!.trigger('click')
    await flushPromises()
    const keyInputs = wrapper.findAll('input[placeholder*="键"], input[placeholder*="英文标识"]')
    await keyInputs[2].setValue('extra_item')
    await button(wrapper, '确定')!.trigger('click')
    await flushPromises()
    expect(mockedRevise).toHaveBeenCalledWith('CT000002', expect.objectContaining({
      clauseFramework: expect.objectContaining({
        slots: expect.arrayContaining([
          expect.objectContaining({ key: 'subject_matter' }),
          expect.objectContaining({ key: 'delivery_mode' }),
          expect.objectContaining({ key: 'extra_item' }),
        ]),
      }),
    }))
    expect(mockedRevise.mock.calls[0][1].clauseFramework.slots).toHaveLength(3)
    // 成功提示全锚（防单字符子串垫绿）
    expect(document.body.textContent).toContain('修订成功：新版本 V3（原 V2 保留）')
    mockedVersions.mockResolvedValue([version(), version({ versionNo: 2 })])
    await button(wrapper, '版本历史')!.trigger('click')
    await flushPromises()
    expect(mockedVersions).toHaveBeenCalledWith('CT000002')
    expect(document.body.textContent).toContain('V2')
  })
})

describe('维护留痕（T6 / R3）', () => {
  it('DENIED_MANAGE 拒绝行渲染：动作 / 模板编号 / 操作者 / 拒绝码', async () => {
    setActorMode('operator')
    mockedLogs.mockResolvedValue(page([tplLog()]))
    const wrapper = await mountPage()
    await button(wrapper, '运营维护')!.trigger('click')
    await flushPromises()
    await button(wrapper, '维护留痕')!.trigger('click')
    await flushPromises()
    expect(mockedLogs).toHaveBeenCalled()
    const text = document.body.textContent
    expect(text).toContain('维护被拒')
    expect(text).toContain('S20260915000001')
    expect(text).toContain('C0003')
  })

  it('空态"暂无维护记录"与翻页传参', async () => {
    setActorMode('operator')
    mockedLogs.mockResolvedValue(page([]))
    const wrapper = await mountPage()
    await button(wrapper, '运营维护')!.trigger('click')
    await flushPromises()
    await button(wrapper, '维护留痕')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('暂无维护记录')
  })

  it('维护留痕翻页交互：下一页按 pageNum=2 传参（翻页用例名实对齐——评审④ P3）', async () => {
    setActorMode('operator')
    mockedLogs.mockResolvedValue(page([tplLog()], 25))
    const wrapper = await mountPage()
    await button(wrapper, '运营维护')!.trigger('click')
    await flushPromises()
    await button(wrapper, '维护留痕')!.trigger('click')
    await flushPromises()
    mockedLogs.mockClear()
    const nextBtn = wrapper.find('.btn-next')
    expect(nextBtn.exists()).toBe(true)
    await nextBtn.trigger('click')
    await flushPromises()
    expect(mockedLogs).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 2 }))
  })
})

describe('认证失效引导（hifi §6.6-6 / §8-10）', () => {
  it('浏览加载 1000C0002：原样提示 + 清演示登录态 + 回登录页', async () => {
    signInDemo()
    mockedTemplates.mockRejectedValue(new ApiError('1000C0002', '认证失败或身份已失效'))
    await mountPage()
    expect(document.body.textContent).toContain('认证失败或身份已失效')
    expect(isDemoAuthed()).toBe(false)
    expect(currentRouter?.currentRoute.value.path).toBe('/login')
  })
})
