import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import EvidenceRecordDrawer from './EvidenceRecordDrawer.vue'
import { getRecordDetail, type EvidenceDetailView } from '../../../api/evidence'

/**
 * 存证详情抽屉测试（WBS-3.8.5 hifi §4 / §8 T4；C-8.1 S2-8 / C-8.2 S1-8）：
 * - 打开时自取 detail（props.evidenceNo）；六要素 descriptions + 状态流转与核验留痕表；
 * - 治理面原文字段（actions.actorSubjectNo）原样渲染（Q3 口径）；
 * - 只读铁律：零业务动作按钮（无核验 / 重试 / 修改 / 删除 / 补录入口，S2-9 / B5-3）；
 * - 关闭经 emit('update:visible') 回传（v-model:visible 双向）。
 */
vi.mock('../../../api/evidence', () => ({
  getRecordDetail: vi.fn(),
}))

const mockedDetail = vi.mocked(getRecordDetail)

function detail(over: Partial<EvidenceDetailView> = {}): EvidenceDetailView {
  return {
    record: {
      evidenceNo: 'EV20261107000006',
      stageType: 'USAGE',
      businessNo: 'USG000000000569',
      operationType: 'USAGE_ALLOWED',
      subjectDidDigest: '0e23678330996e0a0933c1ac7c9af5ad160251db1a18b33ec2e4b85fbd9fc46c',
      occurredAt: '2026-11-07T15:03:31.429Z',
      contentDigest: 'dffaa2a5ad0c7030c293c7c608a9d2b7980e40ceb0c3a6a1851ced90db9ab4f5',
      status: 'FAILED',
      chainTxId: null,
      chainBlockNo: null,
      retryCount: 0,
      lastErrorSummary: '存证链暂不可达',
      createdAt: '2026-11-07T15:03:31.884Z',
      updatedAt: '2026-11-07T15:03:32.031Z',
    },
    actions: [
      {
        action: 'CREATED',
        fromStatus: null,
        toStatus: 'PENDING',
        actorSubjectNo: 'S20260922000001',
        reasonCode: 'NONE',
        detail: null,
        createdAt: '2026-11-07T15:03:31.884Z',
      },
      {
        action: 'CHAIN_FAILED',
        fromStatus: 'PENDING',
        toStatus: 'FAILED',
        actorSubjectNo: 'S20260922000001',
        reasonCode: '1012S0001',
        detail: '存证链暂不可达',
        createdAt: '2026-11-07T15:03:32.031Z',
      },
      {
        action: 'VERIFIED',
        fromStatus: 'ON_CHAIN',
        toStatus: 'ON_CHAIN',
        actorSubjectNo: 'S-admin-evidence',
        reasonCode: 'NONE',
        detail: 'PASS',
        createdAt: '2026-11-08T03:01:35.542Z',
      },
    ],
    ...over,
  }
}

async function openDrawer(evidenceNo = 'EV20261107000006') {
  const wrapper = mount(EvidenceRecordDrawer, {
    attachTo: document.body,
    props: { evidenceNo, visible: false },
    global: { plugins: [ElementPlus] },
  })
  await wrapper.setProps({ visible: true })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.clearAllMocks()
  mockedDetail.mockResolvedValue(detail())
})

describe('T4 详情抽屉（hifi §4）', () => {
  it('visible=false 不发请求；置 true 打开时自取 detail（props.evidenceNo）', async () => {
    const wrapper = mount(EvidenceRecordDrawer, {
      attachTo: document.body,
      props: { evidenceNo: 'EV20261107000006', visible: false },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    expect(mockedDetail).not.toHaveBeenCalled()

    await wrapper.setProps({ visible: true })
    await flushPromises()
    expect(mockedDetail).toHaveBeenCalledTimes(1)
    expect(mockedDetail).toHaveBeenCalledWith('EV20261107000006')
  })

  it('渲染六要素与状态流转留痕表（动作 / 迁移 / 操作人原文 / 结论 / 时点）', async () => {
    const wrapper = await openDrawer()
    const text = wrapper.text()
    // 六要素（record 投影）
    expect(text).toContain('EV20261107000006')
    expect(text).toContain('USG000000000569')
    expect(text).toContain('上链失败')
    expect(text).toContain('存证链暂不可达')
    expect(text).toContain('dffaa2a5ad0c7030c293c7c608a9d2b7980e40ceb0c3a6a1851ced90db9ab4f5')
    // actions 留痕：动作与迁移 + 治理面操作人原文（Q3）+ VERIFIED 行结论
    expect(text).toContain('创建')
    expect(text).toContain('上链失败')
    expect(text).toContain('核验')
    expect(text).toContain('S20260922000001')
    expect(text).toContain('S-admin-evidence')
    expect(text).toContain('1012S0001')
    expect(text).toContain('PASS')
  })

  it('零业务动作按钮（S2-9 / B5-3 观察点）：无重试上链 / 修改 / 删除 / 补录入口', async () => {
    const wrapper = await openDrawer()
    const text = wrapper.text()
    expect(text).not.toContain('重试上链')
    const buttons = wrapper.findAll('button').map((b) => b.text())
    for (const label of buttons) {
      expect(label.match(/重试上链|修改|删除|补录/), `抽屉内不得出现写动作按钮：${label}`).toBeNull()
    }
  })

  it('加载失败：错误原文 el-alert 呈现（ApiError 不转译）', async () => {
    mockedDetail.mockRejectedValue(new Error('记录不存在'))
    const wrapper = await openDrawer('EV20261107000099')
    expect(wrapper.text()).toContain('记录不存在')
  })
})
