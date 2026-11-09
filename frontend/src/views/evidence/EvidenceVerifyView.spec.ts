import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import EvidenceVerifyView from './EvidenceVerifyView.vue'
import {
  getRecordDetail,
  verifyRecord,
  type EvidenceDetailView,
  type EvidenceVerifyView as VerifyResultView,
} from '../../api/evidence'
import { setDemoSubject } from '../../api/client'
import { setActorMode } from '../../stores/demoIdentity'

/**
 * 存证核验工具页测试（WBS-3.8.5 hifi §4 / §8 T11/T12；C-8.1 S2-1 / S2-3 / S2-5 / S2-8）：
 * - 单卡流（Q2-A）：输入存证编号 → 发起核验 → 三态结论卡 + 核验留痕表（detail 端点 actions）；
 * - 三态显式：PASS 绿 / MISMATCH 红（三方摘要定位）/ NOT_ON_CHAIN 橙（当前存证状态提示，
 *   文案由 verify 出参 status 驱动：FAILED → 上链失败〔原因摘要取 detail〕/ PENDING → 待上链）；
 * - HTTP 恒 200：结论不映射 HTTP 错误；动作失败 = ElMessage.error 原文（1012C0001 不存在）；
 * - 空编号前置提示为纯 UI（零请求），编号存在性等业务判定全在后端（Q6-A）；
 * - 只读铁律：核验为唯一"动作"（只读比对），页面零写按钮（S2-9）。
 */
vi.mock('../../api/evidence', () => ({
  getRecordDetail: vi.fn(),
  verifyRecord: vi.fn(),
}))

const mockedVerify = vi.mocked(verifyRecord)
const mockedDetail = vi.mocked(getRecordDetail)

function verifyView(over: Partial<VerifyResultView> = {}): VerifyResultView {
  return {
    conclusion: 'PASS',
    evidenceNo: 'EV20261107000004',
    businessNo: 'DL000031',
    status: 'ON_CHAIN',
    expectedDigest: '58a3eb769af998155b7aa1ca2bbb137a7de9948bacb472a88c9a785fefd0a69b',
    actualDigest: '58a3eb769af998155b7aa1ca2bbb137a7de9948bacb472a88c9a785fefd0a69b',
    localDigest: '58a3eb769af998155b7aa1ca2bbb137a7de9948bacb472a88c9a785fefd0a69b',
    chainTxId: 'LTX000000000004',
    verifiedBy: 'S-admin-evidence',
    verifiedAt: '2026-11-08T03:01:35.184Z',
    ...over,
  }
}

function detail(over: Partial<EvidenceDetailView> = {}): EvidenceDetailView {
  return {
    record: {
      evidenceNo: 'EV20261107000004',
      stageType: 'DELIVERY',
      businessNo: 'DL000031',
      operationType: 'DELIVERY_CREATED',
      subjectDidDigest: '0e23678330996e0a0933c1ac7c9af5ad160251db1a18b33ec2e4b85fbd9fc46c',
      occurredAt: '2026-11-07T15:02:33.000Z',
      contentDigest: '58a3eb769af998155b7aa1ca2bbb137a7de9948bacb472a88c9a785fefd0a69b',
      status: 'ON_CHAIN',
      chainTxId: 'LTX000000000004',
      chainBlockNo: 4,
      retryCount: 0,
      lastErrorSummary: null,
      createdAt: '2026-11-07T15:02:33.419Z',
      updatedAt: '2026-11-07T15:02:33.468Z',
    },
    actions: [
      { action: 'VERIFIED', fromStatus: 'ON_CHAIN', toStatus: 'ON_CHAIN', actorSubjectNo: 'S-admin-evidence', reasonCode: 'NONE', detail: 'PASS', createdAt: '2026-11-08T03:01:35.542Z' },
    ],
    ...over,
  }
}

async function mountView() {
  const wrapper = mount(EvidenceVerifyView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

async function submitNo(wrapper: ReturnType<typeof mount>, evidenceNo: string) {
  await wrapper.find('.verify-no input').setValue(evidenceNo)
  await wrapper.find('.verify-submit-btn').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.clearAllMocks()
  vi.spyOn(ElMessage, 'error')
  vi.spyOn(ElMessage, 'warning')
  vi.spyOn(ElMessage, 'success')
  setDemoSubject('S-admin-evidence')
  setActorMode('operator')
  mockedVerify.mockResolvedValue(verifyView())
  mockedDetail.mockResolvedValue(detail())
})

describe('T11 核验三态结论卡（C-8.1 S2-1 / S2-3 / S2-5）', () => {
  it('PASS：绿色结论卡（链上交易标识 / 块号 / 核验人 / 核验时点）+ 留痕表随核验渲染', async () => {
    const wrapper = await mountView()
    await submitNo(wrapper, 'EV20261107000004')
    expect(mockedVerify).toHaveBeenCalledWith('EV20261107000004')
    expect(mockedDetail).toHaveBeenCalledWith('EV20261107000004')

    const card = wrapper.find('.verify-card-pass')
    expect(card.exists()).toBe(true)
    const text = card.text()
    expect(text).toContain('通过')
    expect(text).toContain('LTX000000000004')
    expect(text).toContain('4')
    expect(text).toContain('S-admin-evidence')
    expect(text).toContain('2026-11-08T03:01:35.184Z')
    // 三方摘要中截断呈现（一致态三值相同；点击复制完整值，hifi §4 通用契约）
    expect(text).toContain('58a3eb76…efd0a69b')
    // 留痕表（B-3）：VERIFIED 行 + 治理档操作人原文
    const trail = wrapper.find('.verify-trail-table')
    expect(trail.exists()).toBe(true)
    expect(wrapper.text()).toContain('S-admin-evidence')
  })

  it('摘要点击复制完整值：clipboard 写入 + 成功提示（hifi §4 通用契约）', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const wrapper = await mountView()
    await submitNo(wrapper, 'EV20261107000004')
    const digestCells = wrapper.findAll('.digest-copy')
    expect(digestCells.length).toBeGreaterThanOrEqual(3)
    await digestCells[0].trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('58a3eb769af998155b7aa1ca2bbb137a7de9948bacb472a88c9a785fefd0a69b')
    expect(ElMessage.success).toHaveBeenCalledWith('已复制完整值')
  })

  it('MISMATCH：红色卡三方摘要（期望 / 实际 / 本地）+ 编号 / 单号 / 时点定位（S2-3 差异可读）', async () => {
    mockedVerify.mockResolvedValue(
      verifyView({
        conclusion: 'MISMATCH',
        evidenceNo: 'EV20261107000001',
        status: 'ON_CHAIN',
        expectedDigest: 'aaa111',
        actualDigest: 'bbb222',
        localDigest: 'ccc333',
      }),
    )
    const wrapper = await mountView()
    await submitNo(wrapper, 'EV20261107000001')
    const card = wrapper.find('.verify-card-mismatch')
    expect(card.exists()).toBe(true)
    const text = card.text()
    expect(text).toContain('不一致')
    expect(text).toContain('aaa111')
    expect(text).toContain('bbb222')
    expect(text).toContain('ccc333')
    expect(text).toContain('EV20261107000001')
    expect(text).toContain('DL000031')
    expect(text).toContain('2026-11-08T03:01:35.184Z')
    expect(wrapper.find('.verify-card-pass').exists()).toBe(false)
  })

  it('NOT_ON_CHAIN + status=FAILED：橙色卡提示"上链失败"并展示原因摘要（取 detail 出参）', async () => {
    mockedVerify.mockResolvedValue(verifyView({ conclusion: 'NOT_ON_CHAIN', status: 'FAILED', chainTxId: null }))
    mockedDetail.mockResolvedValue({
      record: { ...detail().record, status: 'FAILED', lastErrorSummary: '存证链暂不可达' },
      actions: detail().actions,
    })
    const wrapper = await mountView()
    await submitNo(wrapper, 'EV20261107000006')
    const card = wrapper.find('.verify-card-not-on-chain')
    expect(card.exists()).toBe(true)
    const text = card.text()
    expect(text).toContain('未上链')
    expect(text).toContain('上链失败')
    expect(text).toContain('存证链暂不可达')
  })

  it('NOT_ON_CHAIN + status=PENDING：橙色卡提示"待上链"', async () => {
    mockedVerify.mockResolvedValue(verifyView({ conclusion: 'NOT_ON_CHAIN', status: 'PENDING', chainTxId: null }))
    const wrapper = await mountView()
    await submitNo(wrapper, 'EV20261107000002')
    expect(wrapper.find('.verify-card-not-on-chain').text()).toContain('待上链')
  })
})

describe('T12 核验留痕与前置提示（C-8.1 B3-3 / S2-9）', () => {
  it('再次核验后留痕表同步刷新（getRecordDetail 重取）', async () => {
    const wrapper = await mountView()
    await submitNo(wrapper, 'EV20261107000004')
    expect(mockedDetail).toHaveBeenCalledTimes(1)
    await submitNo(wrapper, 'EV20261107000004')
    await flushPromises()
    expect(mockedVerify).toHaveBeenCalledTimes(2)
    expect(mockedDetail).toHaveBeenCalledTimes(2)
  })

  it('空编号前置提示：零请求（纯 UI 引导，非业务校验）', async () => {
    const wrapper = await mountView()
    await submitNo(wrapper, '   ')
    expect(mockedVerify).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('请输入存证编号')
  })

  it('回车提交与按钮提交等效', async () => {
    const wrapper = await mountView()
    await wrapper.find('.verify-no input').setValue('EV20261107000004')
    await wrapper.find('.verify-no input').trigger('keyup.enter')
    await flushPromises()
    expect(mockedVerify).toHaveBeenCalledTimes(1)
  })

  it('编号不存在：后端 1012C0001 原文弹出（B-4，前端零加工）', async () => {
    mockedVerify.mockRejectedValue(new Error('存证记录不存在'))
    const wrapper = await mountView()
    await submitNo(wrapper, 'EV20990101000099')
    expect(ElMessage.error).toHaveBeenCalledWith('存证记录不存在')
  })

  it('只读铁律：页面零写按钮（无重试上链 / 修改 / 删除 / 补录入口，S2-9）', async () => {
    const wrapper = await mountView()
    const buttons = wrapper.findAll('button').map((b) => b.text())
    for (const label of buttons) {
      expect(label.match(/重试上链|修改|删除|补录/), `核验页不得出现写动作按钮：${label}`).toBeNull()
    }
  })
})
