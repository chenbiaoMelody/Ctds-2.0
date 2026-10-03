import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import DatasetManageView from './DatasetManageView.vue'
import {
  registerDataset,
  listMyDatasets,
  updateDataset,
  cancelDataset,
  listVocabularies,
  listTerms,
  listDatasetActionLogs,
  type DatasetItem,
  type TagTerm,
  type DatasetActionLog,
} from '../../api/catalog'
import { listSpaces, type SpaceSummary } from '../../api/space'
import { ApiError, setDemoSubject } from '../../api/client'
import {
  RESOURCE_NAME_REQUIRED_TIP,
  SPACE_ID_REQUIRED_TIP,
  DATASETS_EMPTY_TIP,
  DATASET_LOGS_EMPTY_TIP,
} from '../../constants/catalog'

/**
 * 资源登记页测试（WBS-3.3.6 hifi §6.4 + §6.8 弹窗 A/B/C + §7 T11/T12/T13）：
 * - T11 登记：必填前置（空名称/空空间编号 = 零请求）+ 提交契约字段逐字（tags 传 termName、
 *   declareImportant）+ 未启用空间允许提交并展示 1007C0002 + 重要数据 1007C0003 +
 *   词表外 1007C0009 + 类目外 1007C0014 + 非成员拒 + 成功刷新；
 * - T12 变更/注销：请求体只含可变白名单字段 + 级别下调 1007C0004 原样且不乐观更新 +
 *   注销二次确认（取消 = 零请求）+ 引用保护 1007C0021 原样 + 已注销行无操作入口；
 * - T13 留痕区：四要素 + from→to + 拒绝行渲染 + 空态 + 翻页。
 */
vi.mock('../../api/catalog', () => ({
  registerDataset: vi.fn(),
  listMyDatasets: vi.fn(),
  updateDataset: vi.fn(),
  cancelDataset: vi.fn(),
  listVocabularies: vi.fn(),
  listTerms: vi.fn(),
  listDatasetActionLogs: vi.fn(),
}))
vi.mock('../../api/space', () => ({
  listSpaces: vi.fn(),
}))

const mockedRegister = vi.mocked(registerDataset)
const mockedDatasets = vi.mocked(listMyDatasets)
const mockedUpdate = vi.mocked(updateDataset)
const mockedCancel = vi.mocked(cancelDataset)
const mockedVocabularies = vi.mocked(listVocabularies)
const mockedTerms = vi.mocked(listTerms)
const mockedLogs = vi.mocked(listDatasetActionLogs)
const mockedSpaces = vi.mocked(listSpaces)

function dataset(over: Partial<DatasetItem> = {}): DatasetItem {
  return {
    id: 9,
    dataNo: 'DS20261002000004',
    spaceId: 29,
    name: '小微企业信贷数据集',
    type: 'DATASET',
    intro: '供同名拒绝判定面使用的第二份有效资源',
    tags: ['金融', '风控'],
    declareCategory: '金融',
    declareLevel: 'L2',
    declareImportant: false,
    status: 'ACTIVE',
    createdAt: '2026-10-02T17:58:20',
    ...over,
  }
}

function term(over: Partial<TagTerm> = {}): TagTerm {
  return { termCode: 'TT0001', termName: '金融', ...over }
}

function space(over: Partial<SpaceSummary> = {}): SpaceSummary {
  return {
    id: 29,
    name: '普惠金融空间',
    sceneType: 'FINTECH',
    accessMode: 'OPEN',
    visibility: 'PUBLIC',
    intro: null,
    status: 'ACTIVE',
    effectiveFrom: null,
    effectiveTo: null,
    ...over,
  }
}

function log(over: Partial<DatasetActionLog> = {}): DatasetActionLog {
  return {
    id: 1,
    action: 'REGISTER',
    actorSubjectNo: 'S20260925000001',
    result: 'SUCCESS',
    reasonCode: null,
    fromValue: null,
    toValue: null,
    createdAt: '2026-10-02T17:58:20',
    ...over,
  }
}

function page<T>(list: T[]) {
  return { list, total: list.length, pageNum: 1, pageSize: 10, totalPages: 1 }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/catalog/datasets', name: 'catalog-datasets', component: DatasetManageView },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push('/catalog/datasets')
  await router.isReady()
  const wrapper = mount(DatasetManageView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  for (const fn of [mockedRegister, mockedDatasets, mockedUpdate, mockedCancel,
    mockedVocabularies, mockedTerms, mockedLogs, mockedSpaces]) {
    fn.mockReset()
  }
  setDemoSubject('S20260925000001')
  mockedDatasets.mockResolvedValue(page([dataset()]))
  mockedVocabularies.mockResolvedValue([{ vocabularyCode: 'SEMANTIC_TAG', vocabularyName: '语义标签受控词表' }])
  mockedTerms.mockResolvedValue(page([term(), term({ termCode: 'TT0002', termName: '普惠' })]))
  mockedSpaces.mockResolvedValue(page([space()]))
})

describe('我的资源列表（T11 列表面）', () => {
  it('渲染数据标识 / 名称 / 类型中文 / 状态 / 空间 / 分类分级申报 / 登记时间；空态文案', async () => {
    const wrapper = await mountPage()
    expect(mockedDatasets).toHaveBeenCalledWith(1, 10)
    expect(wrapper.text()).toContain('DS20261002000004')
    expect(wrapper.text()).toContain('小微企业信贷数据集')
    expect(wrapper.text()).toContain('数据集')
    expect(wrapper.text()).toContain('生效中')
    expect(wrapper.text()).toContain('金融')

    mockedDatasets.mockResolvedValue(page([]))
    await wrapper.find('.refresh-btn').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(DATASETS_EMPTY_TIP)
  })

  it('已注销行无变更/注销操作入口（终态）', async () => {
    mockedDatasets.mockResolvedValue(page([dataset({ id: 6, dataNo: 'DS20261002000001', status: 'DELETED' })]))
    const wrapper = await mountPage()
    expect(wrapper.find('.update-btn').exists()).toBe(false)
    expect(wrapper.find('.cancel-btn').exists()).toBe(false)
  })
})

describe('登记资源（T11）', () => {
  async function openDialog() {
    const wrapper = await mountPage()
    await wrapper.find('.register-open').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('必填前置：名称留空 → 零请求；空间编号留空 → 零请求', async () => {
    const wrapper = await openDialog()
    await wrapper.findComponent('.register-type').setValue('DATASET')
    await wrapper.find('.register-intro textarea').setValue('简介')
    await wrapper.findComponent('.register-tags').setValue(['金融'])
    await wrapper.findComponent('.register-category').setValue('金融')
    await wrapper.findComponent('.register-level').setValue('L2')
    await wrapper.find('.register-submit').trigger('click')
    await flushPromises()
    expect(mockedRegister).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain(RESOURCE_NAME_REQUIRED_TIP)

    await wrapper.find('.register-name input').setValue('普惠金融数据集')
    await wrapper.findComponent('.register-space').setValue('')
    await wrapper.find('.register-submit').trigger('click')
    await flushPromises()
    expect(mockedRegister).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain(SPACE_ID_REQUIRED_TIP)
  })

  it('契约字段逐字提交：tags 传 termName 数组、declareImportant 布尔、空间编号直填', async () => {
    mockedRegister.mockResolvedValue(dataset())
    const wrapper = await openDialog()
    await wrapper.find('.register-name input').setValue('普惠金融数据集')
    await wrapper.findComponent('.register-type').setValue('DATASET')
    await wrapper.find('.register-intro textarea').setValue('简介')
    await wrapper.findComponent('.register-tags').setValue(['金融', '风控'])
    await wrapper.findComponent('.register-category').setValue('金融')
    await wrapper.findComponent('.register-level').setValue('L2')
    await wrapper.findComponent('.register-space').setValue('29')
    await wrapper.find('.register-submit').trigger('click')
    await flushPromises()
    expect(mockedRegister).toHaveBeenCalledWith(29, {
      name: '普惠金融数据集',
      type: 'DATASET',
      intro: '简介',
      tags: ['金融', '风控'],
      declareCategory: '金融',
      declareLevel: 'L2',
      declareImportant: false,
    })
    expect(mockedDatasets).toHaveBeenCalledTimes(2)
  })

  it('未启用空间允许提交（界面不拦截），1007C0002 原样展示', async () => {
    mockedRegister.mockRejectedValue(new ApiError('1007C0002', '空间当前状态不允许登记资源'))
    const wrapper = await openDialog()
    await wrapper.find('.register-name input').setValue('普惠金融数据集')
    await wrapper.findComponent('.register-type').setValue('DATASET')
    await wrapper.find('.register-intro textarea').setValue('简介')
    await wrapper.findComponent('.register-tags').setValue(['金融'])
    await wrapper.findComponent('.register-category').setValue('金融')
    await wrapper.findComponent('.register-level').setValue('L2')
    await wrapper.findComponent('.register-space').setValue('30')
    await wrapper.find('.register-submit').trigger('click')
    await flushPromises()
    expect(mockedRegister).toHaveBeenCalledWith(30, expect.objectContaining({ name: '普惠金融数据集' }))
    expect(document.body.textContent).toContain('空间当前状态不允许登记资源')
  })

  it('重要数据申报 1007C0003 / 词表外标签 1007C0009 / 类目外申报 1007C0014 / 非成员 1007C0006 原样展示', async () => {
    const cases: Array<{ code: string; message: string; setup?: () => void }> = [
      { code: '1007C0003', message: '申报为重要数据的资源暂不受理登记' },
      { code: '1007C0009', message: '语义标签不在受控词表范围内' },
      { code: '1007C0014', message: '分类申报不在平台受控类目范围内' },
      { code: '1007C0006', message: '主体未入驻或不存在，无法登记资源' },
    ]
    for (const item of cases) {
      mockedRegister.mockRejectedValue(new ApiError(item.code, item.message))
      document.body.innerHTML = ''
      const wrapper = await openDialog()
      await wrapper.find('.register-name input').setValue('普惠金融数据集')
      await wrapper.findComponent('.register-type').setValue('DATASET')
      await wrapper.find('.register-intro textarea').setValue('简介')
      await wrapper.findComponent('.register-tags').setValue(['火星数据'])
      await wrapper.findComponent('.register-category').setValue('火星类目')
      await wrapper.findComponent('.register-level').setValue('L2')
      await wrapper.findComponent('.register-space').setValue('29')
      await wrapper.find('.register-submit').trigger('click')
      await flushPromises()
      expect(document.body.textContent).toContain(item.message)
      wrapper.unmount()
    }
  })
})

describe('变更与注销（T12）', () => {
  it('变更请求体只含可变白名单字段（仅填简介时不含名称/类型/级别）', async () => {
    mockedUpdate.mockResolvedValue(dataset())
    const wrapper = await mountPage()
    await wrapper.find('.update-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.update-intro textarea').setValue('变更后的简介')
    await wrapper.find('.update-submit').trigger('click')
    await flushPromises()
    expect(mockedUpdate).toHaveBeenCalledWith(9, { intro: '变更后的简介' })
  })

  it('级别下调尝试：以服务端判定为准，1007C0004 原样展示且表格数据不变（不乐观更新）', async () => {
    mockedUpdate.mockRejectedValue(new ApiError('1007C0004', '分类级别变更只能就高收紧，不可放宽'))
    const wrapper = await mountPage()
    await wrapper.find('.update-btn').trigger('click')
    await flushPromises()
    await wrapper.findComponent('.update-level').setValue('L1')
    await wrapper.find('.update-submit').trigger('click')
    await flushPromises()
    expect(mockedUpdate).toHaveBeenCalledWith(9, expect.objectContaining({ declareLevel: 'L1' }))
    expect(document.body.textContent).toContain('分类级别变更只能就高收紧，不可放宽')
    // 不乐观更新：表格仍显示原级别申报
    expect(wrapper.text()).toContain('L2')
  })

  it('注销：二次确认明示不可恢复；取消 = 零请求', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue(new Error('cancel'))
    const wrapper = await mountPage()
    await wrapper.find('.cancel-btn').trigger('click')
    await flushPromises()
    expect(mockedCancel).not.toHaveBeenCalled()
    expect(confirmSpy).toHaveBeenCalled()
    expect(String(confirmSpy.mock.calls[0][0])).toContain('不可恢复')
  })

  it('注销确认后提交：confirmCancellation=true；引用保护 1007C0021 原样展示', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mockedCancel.mockRejectedValue(new ApiError('1007C0021', '资源存在未注销产品引用，请先处理产品'))
    const wrapper = await mountPage()
    await wrapper.find('.cancel-btn').trigger('click')
    await flushPromises()
    expect(mockedCancel).toHaveBeenCalledWith(9)
    expect(document.body.textContent).toContain('资源存在未注销产品引用，请先处理产品')
  })

  it('注销成功：以响应为准刷新列表（已注销行无操作入口）', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mockedCancel.mockResolvedValue({ datasetId: 9, dataNo: 'DS20261002000004', status: 'DELETED', cancelled: true })
    // 首载为有效资源（有注销入口）；注销成功后的刷新读到已注销行（无操作入口）
    mockedDatasets.mockResolvedValueOnce(page([dataset()]))
    mockedDatasets.mockResolvedValue(page([dataset({ status: 'DELETED' })]))
    const wrapper = await mountPage()
    await wrapper.find('.cancel-btn').trigger('click')
    await flushPromises()
    expect(mockedCancel).toHaveBeenCalledWith(9)
    expect(mockedDatasets).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.cancel-btn').exists()).toBe(false)
  })
})

describe('操作留痕区（T13）', () => {
  it('按资源行加载 R14：四要素 + 结果 tag + 拒绝码 + 从何值→到何值', async () => {
    mockedLogs.mockResolvedValue(page([
      log({ id: 3, action: 'UPDATE', fromValue: '旧简介', toValue: '新简介' }),
      log({ id: 4, action: 'DENIED_UPDATE', result: 'DENIED', reasonCode: 'C0006', actorSubjectNo: 'S20260925000009' }),
    ]))
    const wrapper = await mountPage()
    await wrapper.find('.log-btn').trigger('click')
    await flushPromises()
    expect(mockedLogs).toHaveBeenCalledWith(9, 1, 10)
    expect(wrapper.text()).toContain('变更')
    expect(wrapper.text()).toContain('被拒')
    expect(wrapper.text()).toContain('C0006')
    expect(wrapper.text()).toContain('旧简介')
    expect(wrapper.text()).toContain('新简介')
  })

  it('空态与翻页：空列表展示统一空态；重开留痕区后翻页按新页码重新拉取', async () => {
    mockedLogs.mockResolvedValue({ list: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 })
    const wrapper = await mountPage()
    await wrapper.find('.log-btn').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(DATASET_LOGS_EMPTY_TIP)

    // 重新打开留痕区（total=25 分页可用）后翻页
    mockedLogs.mockResolvedValue({ list: [log({ id: 9 })], total: 25, pageNum: 1, pageSize: 10, totalPages: 3 })
    await wrapper.find('.log-btn').trigger('click')
    await flushPromises()
    mockedLogs.mockResolvedValue({ list: [log({ id: 10 })], total: 25, pageNum: 2, pageSize: 10, totalPages: 3 })
    await wrapper.find('.dataset-logs-pager .btn-next').trigger('click')
    await flushPromises()
    expect(mockedLogs).toHaveBeenLastCalledWith(9, 2, 10)
    expect(wrapper.text()).toContain('9')
  })
})
