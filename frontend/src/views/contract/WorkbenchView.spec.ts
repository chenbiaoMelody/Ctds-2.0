import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, DOMWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import WorkbenchView from './WorkbenchView.vue'
import {
  listMyContracts,
  listTemplates,
  getTemplate,
  initiateContract,
  type MyContract,
  type ContractTemplateItem,
  type TemplateDetail,
} from '../../api/contract'
import { searchProducts, type CatalogProduct } from '../../api/catalog'
import { ApiError, setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'
import {
  PRODUCT_NOT_DEALABLE_TIP,
  ADMISSION_REQUIRED_INITIATE_TIP,
  TEMPLATE_NOT_INITIABLE_TIP,
  OPERATOR_MODE_PARTICIPANT_TIP,
  CONTRACT_STATUS_LABELS,
} from '../../constants/contract'

/**
 * 合约工作台测试（WBS-3.4.7 hifi §6.3 + §7 T7/T8）：
 * - T7 我的合约（R6）：状态/角色过滤传参 / 六态渲染（经 constants）/ 分页 / 空态；
 * - T8 发起弹窗（W5）：产品编号直填 +「检索产品」跨域检索（断言调用目录域检索）/
 *   模板下拉（仅启用中；选中锁定版本号传 templateVersionNo）/ 框架槽位表单（必填标记）/
 *   **必填前置（无产品编号/无模板 = 零请求）** / 提交契约字段逐字（productId/templateNo/
 *   templateVersionNo/clauseValues{slots,strategy}）/ C0010·C0016·C0017·C0015 原样 /
 *   成功跳详情。
 */
vi.mock('../../api/contract', () => ({
  listMyContracts: vi.fn(),
  listTemplates: vi.fn(),
  getTemplate: vi.fn(),
  initiateContract: vi.fn(),
}))

vi.mock('../../api/catalog', () => ({
  searchProducts: vi.fn(),
}))

const mockedMine = vi.mocked(listMyContracts)
const mockedTemplates = vi.mocked(listTemplates)
const mockedTemplate = vi.mocked(getTemplate)
const mockedInitiate = vi.mocked(initiateContract)
const mockedSearch = vi.mocked(searchProducts)

function mine(over: Partial<MyContract> = {}): MyContract {
  return {
    contractNo: 'CO000013',
    productName: '城市餐饮单位经营数据集',
    counterpartySubjectNo: 'S20260925000001',
    myRole: 'REQUESTER',
    status: 'EFFECTIVE',
    currentClauseVersion: 1,
    effectiveAt: '2026-10-06T10:00:00',
    updatedAt: '2026-10-06T10:00:00',
    ...over,
  }
}

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
      ],
    },
    publishedAt: '2026-10-05T21:44:11',
    ...over,
  }
}

function product(over: Partial<CatalogProduct> = {}): CatalogProduct {
  return {
    productId: 7,
    productName: '普惠金融数据服务',
    intro: '简介',
    productType: '数据集',
    pricingModel: '按次',
    categoryCode: null,
    categoryName: null,
    providerSubjectNo: 'S20260925000001',
    listedAt: '2026-10-02T21:37:59',
    ...over,
  }
}

function page<T>(list: T[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage(route = '/contracts/workbench') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/contracts/workbench', name: 'contract-workbench', component: WorkbenchView },
      { path: '/contracts/workbench/:contractNo', name: 'contract-detail',
        component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push(route)
  await router.isReady()
  const wrapper = mount(WorkbenchView, {
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
  for (const fn of [mockedMine, mockedTemplates, mockedTemplate, mockedInitiate, mockedSearch]) {
    fn.mockReset()
  }
  setDemoSubject('S20260922000001')
  setActorMode('subject')
  mockedMine.mockResolvedValue(page([mine()]))
})

describe('我的合约列表（T7 / R6）', () => {
  it('列表渲染六要素：编号 / 产品 / 对方主体 / 我的角色（需求方）/ 状态 / 版本', async () => {
    await mountPage()
    expect(document.body.textContent).toContain('CO000013')
    expect(document.body.textContent).toContain('城市餐饮单位经营数据集')
    expect(document.body.textContent).toContain('S20260925000001')
    expect(document.body.textContent).toContain('需求方')
    expect(document.body.textContent).toContain('已生效')
    expect(mockedMine).toHaveBeenCalledWith(expect.objectContaining({ pageNum: 1, pageSize: 10 }))
  })

  it('空态"暂无合约"（空态非报错）', async () => {
    mockedMine.mockResolvedValue(page([]))
    await mountPage()
    expect(document.body.textContent).toContain('暂无合约')
  })

  it('未入驻发起语境统一文案原样（R6 列表被拒时）', async () => {
    mockedMine.mockRejectedValue(new ApiError('1008C0003', ADMISSION_REQUIRED_INITIATE_TIP))
    await mountPage()
    expect(document.body.textContent).toContain('主体未入驻或不存在，无法发起合约')
  })

  it('六态渲染锚定表格状态列 tag（T7：六态逐一挂载——复审④ P3 修正为可证伪断言）', async () => {
    for (const [code, label] of Object.entries(CONTRACT_STATUS_LABELS)) {
      mockedMine.mockResolvedValue(page([mine({ status: code })]))
      const wrapper = await mountPage()
      const tag = wrapper.find('.el-table .el-tag')
      expect(tag.exists(), `状态 ${code} 应在表格状态列渲染 tag`).toBe(true)
      expect(tag.text(), `状态 ${code} 列 tag 文案应经 constants 映射`).toBe(label)
    }
  })
})

describe('发起合约弹窗（T8 / W5）', () => {
  it('必填前置：无产品编号/未选模板时确定 = 零请求', async () => {
    mockedTemplates.mockResolvedValue(page([template()]))
    const wrapper = await mountPage()
    await button(wrapper, '发起合约')!.trigger('click')
    await flushPromises()
    await button(wrapper, '确定')!.trigger('click')
    await flushPromises()
    expect(mockedInitiate).not.toHaveBeenCalled()
  })

  it('检索产品：跨域调用目录域检索并回填产品编号', async () => {
    mockedTemplates.mockResolvedValue(page([template()]))
    mockedSearch.mockResolvedValue(page([product()]))
    const wrapper = await mountPage()
    await button(wrapper, '发起合约')!.trigger('click')
    await flushPromises()
    await button(wrapper, '检索产品')!.trigger('click')
    await flushPromises()
    expect(mockedSearch).toHaveBeenCalled()
    // 结果点选回填编号（检索结果行展示 + 回填动作）
    const pick = button(wrapper, '选择')
    expect(pick).toBeTruthy()
    await pick!.trigger('click')
    await flushPromises()
    const productInput = wrapper.find('input[placeholder*="产品编号"]')
    expect((productInput.element as HTMLInputElement).value).toBe('7')
  })

  it('选模板锁定版本号并按框架渲染必填槽位表单；提交契约字段逐字 + 成功跳详情', async () => {
    const router = await (async () => {
      const r = createRouter({
        history: createMemoryHistory(),
        routes: [
          { path: '/contracts/workbench', name: 'contract-workbench', component: WorkbenchView },
          { path: '/contracts/workbench/:contractNo', name: 'contract-detail',
            component: { template: '<div />' } },
          { path: '/login', name: 'login', component: { template: '<div />' } },
        ],
      })
      await r.push('/contracts/workbench')
      await r.isReady()
      return r
    })()
    mockedTemplates.mockResolvedValue(page([template()]))
    mockedTemplate.mockResolvedValue(templateDetail())
    mockedInitiate.mockResolvedValue({
      contractNo: 'CO000020', status: 'NEGOTIATING', clauseVersionNo: 1,
      templateNo: 'CT000001', templateVersionNo: 1, productId: 7,
    })
    const wrapper = mount(WorkbenchView, {
      attachTo: document.body,
      global: { plugins: [ElementPlus, router] },
    })
    await flushPromises()
    await button(wrapper, '发起合约')!.trigger('click')
    await flushPromises()
    const productInput = wrapper.find('input[placeholder*="产品编号"]')
    await productInput.setValue('7')
    // 模板下拉选 CT000001（原生 select 或 el-select；以 input 触发为准）
    const templateSelect = wrapper.find('.initiate-form select, .initiate-form .el-select')
    expect(templateSelect.exists()).toBe(true)
    const nativeSelect = wrapper.find('.initiate-form select')
    if (nativeSelect.exists()) {
      await nativeSelect.setValue('CT000001')
    } else {
      await templateSelect.trigger('click')
      await flushPromises()
      const option = [...document.body.querySelectorAll('.el-select-dropdown__item')]
        .find((o) => o.textContent?.includes('CT000001'))
      expect(option).toBeTruthy()
      await new DOMWrapper(option as HTMLElement).trigger('click')
    }
    await flushPromises()
    expect(mockedTemplate).toHaveBeenCalledWith('CT000001')
    // 框架槽位表单（必填标记 + 说明）
    expect(document.body.textContent).toContain('标的')
    expect(document.body.textContent).toContain('授权数据范围的描述')
    const slotInput = wrapper.find('.clause-slot-row input, .clause-form input')
    await slotInput.setValue('城市餐饮单位经营数据集')
    mockedSearch.mockClear()
    await button(wrapper, '确定')!.trigger('click')
    await flushPromises()
    expect(mockedInitiate).toHaveBeenCalledWith(expect.objectContaining({
      productId: 7,
      templateNo: 'CT000001',
      templateVersionNo: 1,
      clauseValues: expect.objectContaining({
        slots: expect.objectContaining({ subject_matter: '城市餐饮单位经营数据集' }),
        strategy: expect.objectContaining({ noRestrictionDeclared: expect.any(Boolean) }),
      }),
    }))
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('contract-detail')
  })

  it('产品不可发 C0010 原样展示且状态不变（不做乐观更新）', async () => {
    mockedTemplates.mockResolvedValue(page([template()]))
    mockedTemplate.mockResolvedValue(templateDetail())
    mockedInitiate.mockRejectedValue(new ApiError('1008C0010', PRODUCT_NOT_DEALABLE_TIP))
    const wrapper = await mountPage()
    await button(wrapper, '发起合约')!.trigger('click')
    await flushPromises()
    const productInput = wrapper.find('input[placeholder*="产品编号"]')
    await productInput.setValue('999')
    const nativeSelect = wrapper.find('.initiate-form select')
    if (nativeSelect.exists()) {
      await nativeSelect.setValue('CT000001')
    } else {
      await wrapper.find('.initiate-form .el-select').trigger('click')
      await flushPromises()
      const option = [...document.body.querySelectorAll('.el-select-dropdown__item')]
        .find((o) => o.textContent?.includes('CT000001'))
      expect(option).toBeTruthy()
      await new DOMWrapper(option as HTMLElement).trigger('click')
    }
    await flushPromises()
    const slotInput = wrapper.find('.clause-slot-row input, .clause-form input')
    if (slotInput.exists()) {
      await slotInput.setValue('x')
    }
    await button(wrapper, '确定')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(PRODUCT_NOT_DEALABLE_TIP)
    // 被拒后弹窗仍打开（状态不变，以响应为准）
    expect(wrapper.find('.initiate-form, .el-dialog').exists()).toBe(true)
  })

  /** 发起弹窗填单助手：产品编号 + 模板选择 + 必填槽位（C0016/C0017 原样展示用例共用）。 */
  async function fillInitiateForm(wrapper: ReturnType<typeof mount>, no: string): Promise<void> {
    await button(wrapper, '发起合约')!.trigger('click')
    await flushPromises()
    await wrapper.find('input[placeholder*="产品编号"]').setValue(no)
    const nativeSelect = wrapper.find('.initiate-form select')
    if (nativeSelect.exists()) {
      await nativeSelect.setValue('CT000001')
    } else {
      await wrapper.find('.initiate-form .el-select').trigger('click')
      await flushPromises()
      const option = [...document.body.querySelectorAll('.el-select-dropdown__item')]
        .find((o) => o.textContent?.includes('CT000001'))
      expect(option).toBeTruthy()
      await new DOMWrapper(option as HTMLElement).trigger('click')
    }
    await flushPromises()
    const slotInput = wrapper.find('.clause-slot-row input, .clause-form input')
    if (slotInput.exists()) {
      await slotInput.setValue('x')
    }
    await flushPromises()
  }

  it('模板不可发起 C0017 原样展示（T8 锚点补全——评审④ P2）', async () => {
    mockedTemplates.mockResolvedValue(page([template()]))
    mockedTemplate.mockResolvedValue(templateDetail())
    mockedInitiate.mockRejectedValue(new ApiError('1008C0017', TEMPLATE_NOT_INITIABLE_TIP))
    const wrapper = await mountPage()
    await fillInitiateForm(wrapper, '999')
    await button(wrapper, '确定')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(TEMPLATE_NOT_INITIABLE_TIP)
    expect(mockedInitiate).toHaveBeenCalled()
  })

  it('双方同主体 C0016 原样展示（T8 锚点补全——评审④ P2）', async () => {
    mockedTemplates.mockResolvedValue(page([template()]))
    mockedTemplate.mockResolvedValue(templateDetail())
    mockedInitiate.mockRejectedValue(
      new ApiError('1008C0016', '合约双方须为不同主体，不可对自己提供的产品发起合约'))
    const wrapper = await mountPage()
    await fillInitiateForm(wrapper, '7')
    await button(wrapper, '确定')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('合约双方须为不同主体，不可对自己提供的产品发起合约')
  })

  it('策略条款非法 C0015 发起腿原样展示（T8 锚点补全——复审① P3）', async () => {
    mockedTemplates.mockResolvedValue(page([template()]))
    mockedTemplate.mockResolvedValue(templateDetail())
    mockedInitiate.mockRejectedValue(new ApiError('1008C0015', '策略条款不符合使用控制约定'))
    const wrapper = await mountPage()
    await fillInitiateForm(wrapper, '7')
    await button(wrapper, '确定')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('策略条款不符合使用控制约定')
    expect(mockedInitiate).toHaveBeenCalled()
  })

  it('必填槽位留空：前置拦截零请求（hifi §8-1——评审① P2，非法取值仍可提交）', async () => {
    mockedTemplates.mockResolvedValue(page([template()]))
    mockedTemplate.mockResolvedValue(templateDetail()) // subject_matter 必填槽位
    const wrapper = await mountPage()
    await button(wrapper, '发起合约')!.trigger('click')
    await flushPromises()
    await wrapper.find('input[placeholder*="产品编号"]').setValue('7')
    const nativeSelect = wrapper.find('.initiate-form select')
    if (nativeSelect.exists()) {
      await nativeSelect.setValue('CT000001')
    } else {
      await wrapper.find('.initiate-form .el-select').trigger('click')
      await flushPromises()
      const option = [...document.body.querySelectorAll('.el-select-dropdown__item')]
        .find((o) => o.textContent?.includes('CT000001'))
      expect(option).toBeTruthy()
      await new DOMWrapper(option as HTMLElement).trigger('click')
    }
    await flushPromises()
    // 不填必填槽位直接提交 → 零请求
    await button(wrapper, '确定')!.trigger('click')
    await flushPromises()
    expect(mockedInitiate).not.toHaveBeenCalled()
  })

  it('运营档发起被拒：原样展示 + 体验层切档提示（hifi §4/§8-7——评审① P2）', async () => {
    setActorMode('operator')
    mockedTemplates.mockResolvedValue(page([template()]))
    mockedTemplate.mockResolvedValue(templateDetail())
    mockedInitiate.mockRejectedValue(
      new ApiError('1008C0016', '合约双方须为不同主体，不可对自己提供的产品发起合约'))
    const wrapper = await mountPage()
    await fillInitiateForm(wrapper, '7')
    await button(wrapper, '确定')!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('合约双方须为不同主体，不可对自己提供的产品发起合约')
    expect(document.body.textContent).toContain(OPERATOR_MODE_PARTICIPANT_TIP)
  })
})
