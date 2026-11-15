import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import MonitorView from './MonitorView.vue'
import { getMetrics, type MetricsView, type MonitorServiceView } from '../../api/platform'
import { ApiError, setDemoSubject } from '../../api/client'
import { isDemoAuthed, signInDemo } from '../../stores/demoAuth'

/**
 * 监控读数页测试（WBS-3.9.5 hifi §8 T8/T9；lofi 屏 A；C-10.2 S1-1/S1-3）：
 * - T8 四区块读数表：UP/DOWN 存活徽标 + 读数列渲染；
 * - T9 未接入显式标注：`NOT_INTEGRATED` 行"未接入"徽标 + note 原文，**断言不出现 0 值渲染**（B5-R4）；
 * - 错误态：1013S0003 数据源不可用 ApiError 原文（E-1 勘误实测形态）；
 * - 诚实边界条：不持久化 / 单副本 / 无集群层指标 / 外发通道"待生效"（ADR-014 v1 口径）。
 */
vi.mock('../../api/platform', () => ({
  getMetrics: vi.fn(),
}))

const mockedMetrics = vi.mocked(getMetrics)

function svc(over: Partial<MonitorServiceView> = {}): MonitorServiceView {
  return {
    service: 'evidence-service',
    job: 'evidence',
    integrated: 'INTEGRATED',
    alive: 'UP',
    heapUsedBytes: 268435456,
    heapMaxBytes: 402653184,
    gcPauseSecondsRate5m: 0.012,
    httpRatePerSec1m: 3.2,
    errorRate5xx5m: 0.01,
    p95LatencyMs5m: 88,
    note: null,
    ...over,
  }
}

function metrics(over: Partial<MetricsView> = {}): MetricsView {
  return { services: [svc()], ...over }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/platform/monitor', name: 'platform-monitor', component: MonitorView }],
  })
  await router.push('/platform/monitor')
  await router.isReady()
  const wrapper = mount(MonitorView, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  mockedMetrics.mockReset()
  setDemoSubject('S-admin-platform')
  if (!isDemoAuthed()) {
    signInDemo()
  }
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('监控读数页（T8 四区块）', () => {
  it('挂载即拉 metrics；逐服务行渲染四区块读数（T8）', async () => {
    mockedMetrics.mockResolvedValue(metrics({
      services: [
        svc(),
        svc({ service: 'connector-service', job: 'connector', alive: 'DOWN', httpRatePerSec1m: 1.5, p95LatencyMs5m: 210 }),
      ],
    }))
    const { wrapper } = await mountPage()
    expect(mockedMetrics).toHaveBeenCalledTimes(1)
    const text = document.body.textContent ?? ''
    expect(text).toContain('evidence-service')
    expect(text).toContain('connector-service')
    const rows = wrapper.findAll('.el-table__row')
    expect(rows.length).toBe(2)
    // 存活徽标：UP 与 DOWN 双态可辨
    const upRow = rows.find((row) => row.text().includes('evidence-service'))
    const downRow = rows.find((row) => row.text().includes('connector-service'))
    expect(upRow?.text()).toContain('UP')
    expect(downRow?.text()).toContain('DOWN')
    // 读数列：HTTP 速率与 P95 数值渲染（同源口径，不转译）
    expect(upRow?.text()).toContain('3.2')
    expect(upRow?.text()).toContain('88')
  })

  it('空态：暂无服务读数（不伪造数据）', async () => {
    mockedMetrics.mockResolvedValue(metrics({ services: [] }))
    await mountPage()
    expect(document.body.textContent).toContain('暂无')
  })

  it('错误态：数据源不可用 1013S0003 原文 el-alert + 重试（E-1 勘误实测形态）', async () => {
    mockedMetrics.mockRejectedValue(new ApiError('1013S0003', '监控数据源暂不可用，请稍后重试'))
    const { wrapper } = await mountPage()
    expect(document.body.textContent).toContain('监控数据源暂不可用，请稍后重试')
    // 重试按钮可再次触发拉取
    mockedMetrics.mockResolvedValue(metrics())
    const retry = wrapper.findAll('button').find((b) => b.text().includes('重试'))
    await retry?.trigger('click')
    await flushPromises()
    expect(mockedMetrics).toHaveBeenCalledTimes(2)
  })
})

describe('监控读数页（T9 未接入显式标注，B5-R4）', () => {
  it('NOT_INTEGRATED 行：未接入徽标 + note 原文，读数列不渲染 0 值', async () => {
    mockedMetrics.mockResolvedValue(metrics({
      services: [
        svc(),
        svc({
          service: 'metering-billing',
          job: 'metering',
          integrated: 'NOT_INTEGRATED',
          alive: null,
          heapUsedBytes: null,
          heapMaxBytes: null,
          gcPauseSecondsRate5m: null,
          httpRatePerSec1m: null,
          errorRate5xx5m: null,
          p95LatencyMs5m: null,
          note: '未接入指标采集，读数不可用',
        }),
      ],
    }))
    const { wrapper } = await mountPage()
    const rows = wrapper.findAll('.el-table__row')
    const notIntegratedRow = rows.find((row) => row.text().includes('metering-billing'))
    expect(notIntegratedRow).toBeDefined()
    // note 原文透传（未接入标注，B5-R4）
    expect(notIntegratedRow?.text()).toContain('未接入指标采集，读数不可用')
    // 不出现伪造读数：该行不渲染 0 值读数（含 0.00 / 0 req/s / 0 ms / 0% 形态，评审④ P3 补强）
    expect(notIntegratedRow?.text()).not.toContain('0.00')
    expect(notIntegratedRow?.text()).not.toMatch(/0\s*(req\/s|ms|%)/)
    // 未接入字样在行内可见
    expect(notIntegratedRow?.text()).toContain('未接入')
  })

  it('已接入行：存活 UP 徽标与读数正常渲染（与未接入行同表对照）', async () => {
    mockedMetrics.mockResolvedValue(metrics())
    const { wrapper } = await mountPage()
    const rows = wrapper.findAll('.el-table__row')
    expect(rows[0]?.text()).toContain('UP')
    expect(rows[0]?.text()).not.toContain('未接入指标采集')
  })
})

describe('监控读数页（诚实边界条，S2-9）', () => {
  it('页底诚实边界提示：不持久化 / 单副本 / 无集群层指标 / 外发通道待生效（ADR-014 v1 口径）', async () => {
    mockedMetrics.mockResolvedValue(metrics())
    await mountPage()
    const text = document.body.textContent ?? ''
    expect(text).toContain('不持久化')
    expect(text).toContain('单副本')
    expect(text).toContain('集群')
    expect(text).toContain('待生效')
  })
})
