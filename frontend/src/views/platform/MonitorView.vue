<template>
  <div class="platform-monitor-page">
    <div class="page-head">
      <h2 class="page-title">监控读数</h2>
      <div class="page-desc">读数与 <code>/actuator/prometheus</code> 同源，未接入服务如实标注（不显示 0 或绿）。</div>
    </div>

    <el-alert
      v-if="error"
      class="monitor-error"
      type="error"
      :title="error.message"
      :closable="false"
      show-icon
    >
      <el-button link type="primary" @click="reload">重试</el-button>
    </el-alert>

    <el-card v-else shadow="never" class="monitor-card">
      <el-table
        v-loading="loading"
        class="monitor-table"
        size="small"
        :data="metrics?.services ?? []"
        :span-method="spanMethod"
        :empty-text="MONITOR_EMPTY_TEXT"
      >
        <el-table-column label="服务" min-width="180">
          <template #default="scope">{{ scope.row.service }}</template>
        </el-table-column>
        <el-table-column label="存活" width="110">
          <template #default="scope">
            <el-tag
              v-if="scope.row.integrated === 'NOT_INTEGRATED'"
              type="info"
            >
              {{ labelOf(INTEGRATED_LABELS, scope.row.integrated) }}
            </el-tag>
            <template v-else>{{ scope.row.alive ?? '—' }}</template>
          </template>
        </el-table-column>
        <el-table-column label="堆内存 / GC" min-width="170">
          <template #default="scope">
            <template v-if="isIntegrated(scope.row)">
              {{ heapText(scope.row) }} · GC {{ gcText(scope.row) }}
            </template>
            <template v-else>{{ noteText(scope.row) }}</template>
          </template>
        </el-table-column>
        <el-table-column label="HTTP 速率" min-width="120">
          <template #default="scope">
            <template v-if="isIntegrated(scope.row)">
              {{ httpText(scope.row) }}
            </template>
            <template v-else>{{ noteText(scope.row) }}</template>
          </template>
        </el-table-column>
        <el-table-column label="5xx 错误率 / P95" min-width="150">
          <template #default="scope">
            <template v-if="isIntegrated(scope.row)">
              {{ errorRateText(scope.row) }} · P95 {{ p95Text(scope.row) }}
            </template>
            <template v-else>{{ noteText(scope.row) }}</template>
          </template>
        </el-table-column>
        <template #empty>{{ MONITOR_EMPTY_TEXT }}</template>
      </el-table>
    </el-card>

    <div class="honesty-bar">
      监控读数不持久化（ADR-014 v1）· 单副本部署 · 无集群层指标 · 告警通知外发通道待生效（G-04）。
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getMetrics, type MetricsView, type MonitorServiceView } from '../../api/platform'
import { INTEGRATED_LABELS, labelOf } from '../../constants/platform'
import { getDemoSubject } from '../../api/client'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../../constants/space'

/**
 * WBS-3.9.5「监控读数」页（hifi §4 / lofi 屏 A，menuOrder 28，platform.monitor.read）：
 * 四区块读数表（服务 / 存活 / 堆内存与 GC / HTTP 速率 / 5xx 与 P95）——直渲染后端口径
 * （B5-R2 原文口径零重编）；`NOT_INTEGRATED` 行 = "未接入"灰徽标 + note 原文合并占满读数列，
 * 绝不显示 0 或绿（B5-R4）；页底诚实边界条（不持久化 / 单副本 / 无集群层指标 / G-04 待生效，
 * ADR-014 v1 不做口径）。数据源不可用（无监控栈）= ApiError 原文错误态 + 重试（E-1）。
 */
const MONITOR_EMPTY_TEXT = '暂无服务读数'

const loading = ref(false)
const metrics = ref<MetricsView | null>(null)
const error = ref<Error | null>(null)

function isIntegrated(row: MonitorServiceView): boolean {
  return row.integrated !== 'NOT_INTEGRATED'
}

function noteText(row: MonitorServiceView): string {
  return row.note ?? '—'
}

function heapText(row: MonitorServiceView): string {
  if (row.heapUsedBytes === null || row.heapMaxBytes === null) return '—'
  return `${formatBytes(row.heapUsedBytes)} / ${formatBytes(row.heapMaxBytes)}`
}

function formatBytes(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function gcText(row: MonitorServiceView): string {
  if (row.gcPauseSecondsRate5m === null) return '—'
  return String(row.gcPauseSecondsRate5m)
}

function httpText(row: MonitorServiceView): string {
  if (row.httpRatePerSec1m === null) return '—'
  return `${row.httpRatePerSec1m} req/s`
}

function errorRateText(row: MonitorServiceView): string {
  if (row.errorRate5xx5m === null) return '—'
  return `${(row.errorRate5xx5m * 100).toFixed(1)}%`
}

function p95Text(row: MonitorServiceView): string {
  if (row.p95LatencyMs5m === null) return '—'
  return `${row.p95LatencyMs5m} ms`
}

/**
 * 未接入行读数列合并：列 2~4（堆内存 / HTTP / 5xx 与 P95）并为 1 格渲染 note 原文
 * （hifi §4"读数四列合并显示 note 原文"；列 0 服务 / 列 1 存活照常）。
 */
function spanMethod({ row, columnIndex }: { row: MonitorServiceView; columnIndex: number }) {
  if (row.integrated === 'NOT_INTEGRATED' && columnIndex >= 2) {
    return columnIndex === 2 ? { rowspan: 1, colspan: 3 } : { rowspan: 0, colspan: 0 }
  }
  return undefined
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    metrics.value = await getMetrics()
  } catch (err) {
    error.value = err instanceof Error ? err : new Error(String(err))
  } finally {
    loading.value = false
  }
}

function reload(): void {
  void load()
}

onMounted(() => {
  if (!getDemoSubject().trim()) {
    error.value = new Error(DEMO_SUBJECT_REQUIRED_TIP)
    return
  }
  void load()
})
</script>

<style scoped>
.platform-monitor-page {
  padding: 16px;
}
.page-head {
  margin-bottom: 12px;
}
.page-title {
  margin: 0 0 4px;
  font-size: 18px;
}
.page-desc {
  color: #909399;
  font-size: 12px;
}
.monitor-error {
  margin-bottom: 12px;
}
.monitor-card {
  margin-bottom: 10px;
}
.honesty-bar {
  padding: 6px 10px;
  background: #f5f7fa;
  color: #909399;
  font-size: 12px;
}
</style>
