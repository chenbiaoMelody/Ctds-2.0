<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  auditAnomalies,
  auditOverview,
  auditTrails,
  downloadAuditReport,
  generateReport,
  listRecords,
  type AnomalyItemView,
  type AuditOverview,
  type AuditReportView,
  type EvidenceRecordView,
  type TrailView,
} from '../../api/evidence'
import type { PageData } from '../../api/types'
import {
  EVIDENCE_ACCESS_HINT,
  EVIDENCE_ACTION_LABELS,
  EVIDENCE_ANOMALY_LABELS,
  EVIDENCE_ANOMALY_TAG,
  EVIDENCE_AUDIT_PAGE_DESC,
  EVIDENCE_AUDIT_PAGE_TITLE,
  EVIDENCE_CONDITION_HINT,
  EVIDENCE_COPY_FAIL_TIP,
  EVIDENCE_COPY_SUCCESS_TIP,
  EVIDENCE_DIGEST_HINT,
  EVIDENCE_EMPTY_TEXT,
  EVIDENCE_GENERATE_LABEL,
  EVIDENCE_JSON_LABEL,
  EVIDENCE_CSV_LABEL,
  EVIDENCE_REDOWNLOAD_NO_TIP,
  EVIDENCE_REDOWNLOAD_TITLE,
  EVIDENCE_RETRY_LABEL,
  EVIDENCE_SEARCH_LABEL,
  EVIDENCE_SNAPSHOT_PREFIX,
  EVIDENCE_STAGE_LABELS,
  EVIDENCE_STATUS_LABELS,
  EVIDENCE_STATUS_TAG,
  EVIDENCE_TAB_LABELS,
  EVIDENCE_TRAIL_HINT_DEFAULT,
  EVIDENCE_TRAIL_SOURCE_LABELS,
  EVIDENCE_TRAIL_STATUS_PREFIX,
  EVIDENCE_UNAVAILABLE_WARNING,
  EVIDENCE_VERIFY_CONCLUSION_LABELS,
  EVIDENCE_VERIFY_CONCLUSION_TAG,
  EVIDENCE_WINDOW_HINT,
  EVIDENCE_WINDOW_HINT_ONLY,
  isBeyond90Days,
  labelOf,
  orDash,
  truncateDigest,
} from '../../constants/evidence'
import EvidenceRecordDrawer from './components/EvidenceRecordDrawer.vue'

/**
 * 监管审计页（WBS-3.8.5 hifi §4；lofi 屏 A 五标签页）：
 * - 五 tab 懒加载：无条件型（总览 / 异常项）首次激活自动拉；条件型（存证记录 / 时间线 / 报告）
 *   由用户查询驱动（≥1 条件由后端裁决，Q6-A——零条件点击照发请求、错误原文展示）；
 * - 界面只读：全页零写按钮（重试上链无入口，N9 / B5-3）；行点击仅开详情抽屉；
 * - 出参原样渲染（Q3）：审计面摘要即摘要、治理面（records）摘要字段照端点口径，零二次加工；
 * - E5 双形态分流在 api 层 downloadAuditReport（裸 fetch 豁免 №2），本页仅 Blob 落 a[download]。
 */
const activeTab = ref('audit-overview')

/** 懒加载记录：无条件型 tab 首次激活自动拉取，此后切回不再重拉。 */
const tabLoaded: Record<string, boolean> = {}

// ==== T2 审计总览（A-1） ====
const overviewData = ref<AuditOverview | null>(null)
const overviewLoading = ref(false)
const overviewError = ref('')
const overviewRange = ref<[string, string] | null>(null)

async function loadOverview() {
  overviewLoading.value = true
  overviewError.value = ''
  try {
    overviewData.value = await auditOverview(overviewRange.value?.[0], overviewRange.value?.[1])
  } catch (e) {
    overviewError.value = (e as Error).message
  } finally {
    overviewLoading.value = false
  }
}

const matrixRows = computed(() =>
  Object.entries(overviewData.value?.matrix ?? {}).map(([stage, counts]) => ({
    stage,
    PENDING: counts.PENDING ?? 0,
    ON_CHAIN: counts.ON_CHAIN ?? 0,
    FAILED: counts.FAILED ?? 0,
  })),
)

function jumpToAnomalies() {
  activeTab.value = 'audit-anomalies'
}

// ==== T3 存证记录（A-2，条件驱动） ====
const recordStage = ref('')
const recordBusinessNo = ref('')
const recordStatus = ref('')
const recordRange = ref<[string, string] | null>(null)
const records = ref<PageData<EvidenceRecordView> | null>(null)
const recordsLoading = ref(false)
const recordsError = ref('')
const recordPageNum = ref(1)
const recordPageSize = ref(10)

async function searchRecords(page = 1) {
  recordPageNum.value = page
  recordsLoading.value = true
  recordsError.value = ''
  try {
    records.value = await listRecords({
      pageNum: recordPageNum.value,
      pageSize: recordPageSize.value,
      stageType: recordStage.value.trim() || undefined,
      businessNo: recordBusinessNo.value.trim() || undefined,
      status: recordStatus.value || undefined,
      from: recordRange.value?.[0],
      to: recordRange.value?.[1],
    })
  } catch (e) {
    recordsError.value = (e as Error).message
    records.value = null
  } finally {
    recordsLoading.value = false
  }
}

function onRecordPageChange(page: number) {
  void searchRecords(page)
}

// ==== T5 异常项（A-3，首激活自动拉；时间范围可选——lofi A-3①，E2 from/to 透传） ====
const anomalies = ref<PageData<AnomalyItemView> | null>(null)
const anomaliesLoading = ref(false)
const anomaliesError = ref('')
const anomalyPageNum = ref(1)
const anomalyPageSize = ref(10)
const anomalyRange = ref<[string, string] | null>(null)

async function loadAnomalies(page = 1) {
  anomalyPageNum.value = page
  anomaliesLoading.value = true
  anomaliesError.value = ''
  try {
    anomalies.value = await auditAnomalies({
      pageNum: anomalyPageNum.value,
      pageSize: anomalyPageSize.value,
      from: anomalyRange.value?.[0],
      to: anomalyRange.value?.[1],
    })
  } catch (e) {
    anomaliesError.value = (e as Error).message
    anomalies.value = null
  } finally {
    anomaliesLoading.value = false
  }
}

function onAnomalyPageChange(page: number) {
  void loadAnomalies(page)
}

// ==== T6/T7 时间线与调阅（A-4，双模） ====
const trailBusinessNo = ref('')
const trailStage = ref('')
const trailSubjectDigest = ref('')
const trailRange = ref<[string, string] | null>(null)
const trailData = ref<TrailView | null>(null)
const trailLoading = ref(false)
const trailError = ref('')
const trailPageNum = ref(1)
const trailPageSize = ref(10)

async function searchTrails(page = 1) {
  trailPageNum.value = page
  trailLoading.value = true
  trailError.value = ''
  try {
    const no = trailBusinessNo.value.trim()
    if (no) {
      trailData.value = await auditTrails({ businessNo: no })
    } else {
      trailData.value = await auditTrails({
        stageType: trailStage.value || undefined,
        subjectDigest: trailSubjectDigest.value.trim() || undefined,
        from: trailRange.value?.[0],
        to: trailRange.value?.[1],
        pageNum: trailPageNum.value,
        pageSize: trailPageSize.value,
      })
    }
  } catch (e) {
    trailError.value = (e as Error).message
    trailData.value = null
  } finally {
    trailLoading.value = false
  }
}

function onTrailPageChange(page: number) {
  void searchTrails(page)
}

/** 跨域业务留痕任一节点不可达 → 顶部警示条（降级显式，不伪装连续）。 */
const trailHasUnavailable = computed(
  () => trailData.value?.businessTrail?.some((node) => node.sourceStatus === 'UNAVAILABLE') ?? false,
)

// ==== T9/T10 审计报告（A-5） ====
const reportStage = ref('')
const reportBusinessNo = ref('')
const reportSubjectDigest = ref('')
const reportRange = ref<[string, string] | null>(null)
const reportRemark = ref('')
const reportResult = ref<AuditReportView | null>(null)
const reportLoading = ref(false)
const reportError = ref('')

async function generate() {
  reportLoading.value = true
  reportError.value = ''
  try {
    reportResult.value = await generateReport({
      stageType: reportStage.value || undefined,
      businessNo: reportBusinessNo.value.trim() || undefined,
      subjectDigest: reportSubjectDigest.value.trim() || undefined,
      from: reportRange.value?.[0],
      to: reportRange.value?.[1],
      remark: reportRemark.value.trim() || undefined,
    })
  } catch (e) {
    reportError.value = (e as Error).message
    reportResult.value = null
  } finally {
    reportLoading.value = false
  }
}

async function downloadReport(reportNo: string, format: 'csv' | 'json') {
  try {
    const { blob, fileName } = await downloadAuditReport(reportNo, format)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

/** 摘要点击复制完整值（hifi §4 通用契约；沿 did/IndexView 先例 navigator.clipboard）。 */
async function copyDigest(value: string | null | undefined) {
  const text = value ?? ''
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(EVIDENCE_COPY_SUCCESS_TIP)
  } catch {
    ElMessage.warning(EVIDENCE_COPY_FAIL_TIP)
  }
}

const redownloadNo = ref('')

function submitRedownload(format: 'csv' | 'json') {
  const no = redownloadNo.value.trim()
  if (!no) {
    ElMessage.error(EVIDENCE_REDOWNLOAD_NO_TIP)
    return
  }
  void downloadReport(no, format)
}

// ==== 90 天窗口体验引导（Q6-A：纯 UI 限选非第二裁决；datetimerange 首选日 + 跨度判定） ====
const overviewFirstPick = ref<number | null>(null)
const recordFirstPick = ref<number | null>(null)
const anomalyFirstPick = ref<number | null>(null)
const trailFirstPick = ref<number | null>(null)
const reportFirstPick = ref<number | null>(null)

function onOverviewPick(dates: Date[] | null) {
  overviewFirstPick.value = dates?.[0]?.getTime() ?? null
}

function limitOverview(date: Date) {
  return isBeyond90Days(overviewFirstPick.value, date.getTime())
}

function onRecordPick(dates: Date[] | null) {
  recordFirstPick.value = dates?.[0]?.getTime() ?? null
}

function limitRecord(date: Date) {
  return isBeyond90Days(recordFirstPick.value, date.getTime())
}

function onAnomalyPick(dates: Date[] | null) {
  anomalyFirstPick.value = dates?.[0]?.getTime() ?? null
}

function limitAnomaly(date: Date) {
  return isBeyond90Days(anomalyFirstPick.value, date.getTime())
}

function onTrailPick(dates: Date[] | null) {
  trailFirstPick.value = dates?.[0]?.getTime() ?? null
}

function limitTrail(date: Date) {
  return isBeyond90Days(trailFirstPick.value, date.getTime())
}

function onReportPick(dates: Date[] | null) {
  reportFirstPick.value = dates?.[0]?.getTime() ?? null
}

function limitReport(date: Date) {
  return isBeyond90Days(reportFirstPick.value, date.getTime())
}

// ==== 详情抽屉（A-2 / A-3 行点击；只读） ====
const drawerVisible = ref(false)
const drawerEvidenceNo = ref('')

function openDrawer(row: EvidenceRecordView) {
  drawerEvidenceNo.value = row.evidenceNo
  drawerVisible.value = true
}

watch(
  activeTab,
  (tab) => {
    if (tabLoaded[tab]) return
    tabLoaded[tab] = true
    if (tab === 'audit-overview') void loadOverview()
    if (tab === 'audit-anomalies') void loadAnomalies()
  },
  { immediate: true },
)
</script>

<template>
  <div class="evidence-audit-page">
    <h2 class="page-title">{{ EVIDENCE_AUDIT_PAGE_TITLE }}</h2>
    <p class="page-desc">{{ EVIDENCE_AUDIT_PAGE_DESC }}</p>

    <el-tabs v-model="activeTab">
      <!-- A-1 审计总览：E1 矩阵 + 异常三计数卡 + 快照时点 -->
      <el-tab-pane :label="EVIDENCE_TAB_LABELS.overview" name="audit-overview">
        <div class="filter-bar">
          <el-date-picker
            v-model="overviewRange"
            class="overview-range"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            start-placeholder="起始时间（可选）"
            end-placeholder="结束时间（可选）"
            style="width: 340px"
            @calendar-change="onOverviewPick"
            :disabled-date="limitOverview"
          />
          <span class="hint-text">{{ EVIDENCE_WINDOW_HINT }}</span>
          <el-button type="primary" class="overview-search-btn" :loading="overviewLoading" @click="loadOverview">
            {{ EVIDENCE_SEARCH_LABEL }}
          </el-button>
        </div>
        <el-alert v-if="overviewError" class="list-error" :title="overviewError" type="error" :closable="false" show-icon>
          <template #default>
            <el-button link type="primary" class="retry-btn" @click="loadOverview">{{ EVIDENCE_RETRY_LABEL }}</el-button>
          </template>
        </el-alert>
        <div v-loading="overviewLoading">
          <el-table v-if="overviewData" :data="matrixRows" class="matrix-table" size="small">
            <el-table-column label="环节" width="120">
              <template #default="scope">{{ labelOf(EVIDENCE_STAGE_LABELS, scope.row.stage) }}</template>
            </el-table-column>
            <el-table-column :label="EVIDENCE_STATUS_LABELS.PENDING" align="center">
              <template #default="scope">{{ scope.row.PENDING }}</template>
            </el-table-column>
            <el-table-column :label="EVIDENCE_STATUS_LABELS.ON_CHAIN" align="center">
              <template #default="scope">{{ scope.row.ON_CHAIN }}</template>
            </el-table-column>
            <el-table-column :label="EVIDENCE_STATUS_LABELS.FAILED" align="center">
              <template #default="scope">
                <el-tag v-if="scope.row.FAILED > 0" type="danger" size="small">{{ scope.row.FAILED }}</el-tag>
                <span v-else>{{ scope.row.FAILED }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <span class="empty-tip">{{ EVIDENCE_EMPTY_TEXT }}</span>
            </template>
          </el-table>

          <div v-if="overviewData" class="anomaly-cards">
            <div class="anomaly-card anomaly-card-pending" @click="jumpToAnomalies">
              <div class="anomaly-card-num">{{ overviewData.anomalies.pendingCount }}</div>
              <div class="anomaly-card-label">待上链</div>
            </div>
            <div class="anomaly-card anomaly-card-failed" @click="jumpToAnomalies">
              <div class="anomaly-card-num">{{ overviewData.anomalies.failedCount }}</div>
              <div class="anomaly-card-label">上链失败</div>
            </div>
            <div class="anomaly-card anomaly-card-mismatch" @click="jumpToAnomalies">
              <div class="anomaly-card-num">{{ overviewData.anomalies.mismatchCount }}</div>
              <div class="anomaly-card-label">核验不一致</div>
            </div>
          </div>
          <p v-if="overviewData" class="snapshot-hint">{{ EVIDENCE_SNAPSHOT_PREFIX }}{{ overviewData.snapshotAt }}</p>
        </div>
      </el-tab-pane>

      <!-- A-2 存证记录：五维过滤 + 分页表 + 行点击详情抽屉（条件驱动，零条件由后端裁决） -->
      <el-tab-pane :label="EVIDENCE_TAB_LABELS.records" name="audit-records">
        <div class="filter-bar">
          <el-select v-model="recordStage" class="records-stage" clearable placeholder="环节" style="width: 110px">
            <el-option v-for="(label, value) in EVIDENCE_STAGE_LABELS" :key="value" :label="label" :value="value" />
          </el-select>
          <el-input v-model="recordBusinessNo" class="records-business-no" placeholder="业务单据号" clearable style="width: 170px" />
          <el-select v-model="recordStatus" class="records-status" clearable placeholder="状态" style="width: 110px">
            <el-option v-for="(label, value) in EVIDENCE_STATUS_LABELS" :key="value" :label="label" :value="value" />
          </el-select>
          <el-date-picker
            v-model="recordRange"
            class="records-range"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            start-placeholder="起始时间"
            end-placeholder="结束时间"
            style="width: 340px"
            @calendar-change="onRecordPick"
            :disabled-date="limitRecord"
          />
          <el-button type="primary" class="records-search-btn" :loading="recordsLoading" @click="searchRecords(1)">
            {{ EVIDENCE_SEARCH_LABEL }}
          </el-button>
        </div>
        <el-alert v-if="recordsError" class="list-error" :title="recordsError" type="error" :closable="false" show-icon>
          <template #default>
            <el-button link type="primary" class="retry-btn" @click="searchRecords(recordPageNum)">{{ EVIDENCE_RETRY_LABEL }}</el-button>
          </template>
        </el-alert>
        <el-table
          v-loading="recordsLoading"
          :data="records?.list ?? []"
          class="records-table"
          size="small"
          @row-click="openDrawer"
        >
          <el-table-column prop="evidenceNo" label="存证编号" width="150" />
          <el-table-column label="环节" width="80">
            <template #default="scope">{{ labelOf(EVIDENCE_STAGE_LABELS, scope.row.stageType) }}</template>
          </el-table-column>
          <el-table-column prop="businessNo" label="业务单据号" width="150" show-overflow-tooltip />
          <el-table-column prop="operationType" label="操作类型" width="160" show-overflow-tooltip />
          <el-table-column label="主体（DID 摘要）" min-width="200" show-overflow-tooltip>
            <template #default="scope">{{ scope.row.subjectDidDigest }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="scope">
              <el-tag size="small" :type="EVIDENCE_STATUS_TAG[scope.row.status] ?? 'info'">
                {{ labelOf(EVIDENCE_STATUS_LABELS, scope.row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="链上标识" width="140">
            <template #default="scope">{{ orDash(scope.row.chainTxId) }}</template>
          </el-table-column>
          <el-table-column prop="occurredAt" label="发生时点" width="180" />
          <template #empty>
            <span class="empty-tip">{{ EVIDENCE_EMPTY_TEXT }}</span>
          </template>
        </el-table>
        <el-pagination
          v-if="(records?.total ?? 0) > recordPageSize"
          class="records-pagination"
          layout="total, prev, pager, next"
          :total="records?.total ?? 0"
          :page-size="recordPageSize"
          :current-page="recordPageNum"
          @current-change="onRecordPageChange"
        />
      </el-tab-pane>

      <!-- A-3 异常项：时间范围（可选）+ 三类并集分页（MISMATCH 行附核验结论 / 核验人摘要 / 时点） -->
      <el-tab-pane :label="EVIDENCE_TAB_LABELS.anomalies" name="audit-anomalies">
        <div class="filter-bar">
          <el-date-picker
            v-model="anomalyRange"
            class="anomalies-range"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            start-placeholder="起始时间（可选）"
            end-placeholder="结束时间（可选）"
            style="width: 340px"
            @calendar-change="onAnomalyPick"
            :disabled-date="limitAnomaly"
          />
          <el-button class="anomalies-search-btn" :loading="anomaliesLoading" @click="loadAnomalies(1)">
            {{ EVIDENCE_SEARCH_LABEL }}
          </el-button>
        </div>
        <el-alert v-if="anomaliesError" class="list-error" :title="anomaliesError" type="error" :closable="false" show-icon>
          <template #default>
            <el-button link type="primary" class="retry-btn" @click="loadAnomalies(anomalyPageNum)">{{ EVIDENCE_RETRY_LABEL }}</el-button>
          </template>
        </el-alert>
        <el-table
          v-loading="anomaliesLoading"
          :data="anomalies?.list ?? []"
          class="anomalies-table"
          size="small"
          @row-click="openDrawer"
        >
          <el-table-column prop="evidenceNo" label="存证编号" width="150" />
          <el-table-column label="环节" width="80">
            <template #default="scope">{{ labelOf(EVIDENCE_STAGE_LABELS, scope.row.stageType) }}</template>
          </el-table-column>
          <el-table-column prop="businessNo" label="业务单据号" width="150" show-overflow-tooltip />
          <el-table-column label="异常类型" width="110">
            <template #default="scope">
              <el-tag size="small" :type="EVIDENCE_ANOMALY_TAG[scope.row.anomalyType] ?? 'info'">
                {{ labelOf(EVIDENCE_ANOMALY_LABELS, scope.row.anomalyType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="当前状态" width="100">
            <template #default="scope">
              <el-tag size="small" :type="EVIDENCE_STATUS_TAG[scope.row.status] ?? 'info'">
                {{ labelOf(EVIDENCE_STATUS_LABELS, scope.row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最新核验结论" width="110">
            <template #default="scope">
              <el-tag
                v-if="scope.row.verifyConclusion"
                size="small"
                :type="EVIDENCE_VERIFY_CONCLUSION_TAG[scope.row.verifyConclusion] ?? 'info'"
              >
                {{ labelOf(EVIDENCE_VERIFY_CONCLUSION_LABELS, scope.row.verifyConclusion) }}
              </el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="核验人" min-width="180" show-overflow-tooltip>
            <template #default="scope">{{ orDash(scope.row.verifiedBy) }}</template>
          </el-table-column>
          <el-table-column label="核验时点" width="180">
            <template #default="scope">{{ orDash(scope.row.verifiedAt) }}</template>
          </el-table-column>
          <el-table-column label="失败原因摘要" min-width="140" show-overflow-tooltip>
            <template #default="scope">{{ orDash(scope.row.lastErrorSummary) }}</template>
          </el-table-column>
          <template #empty>
            <span class="empty-tip">{{ EVIDENCE_EMPTY_TEXT }}</span>
          </template>
        </el-table>
        <el-pagination
          v-if="(anomalies?.total ?? 0) > anomalyPageSize"
          class="anomalies-pagination"
          layout="total, prev, pager, next"
          :total="anomalies?.total ?? 0"
          :page-size="anomalyPageSize"
          :current-page="anomalyPageNum"
          @current-change="onAnomalyPageChange"
        />
      </el-tab-pane>

      <!-- A-4 时间线与调阅：双模（单笔 TRAIL / 清单 LIST）+ UNAVAILABLE 降级显式 -->
      <el-tab-pane :label="EVIDENCE_TAB_LABELS.trails" name="audit-trails">
        <div class="filter-bar">
          <el-input v-model="trailBusinessNo" class="trail-business-no" placeholder="业务单据号（单笔全链）" clearable style="width: 200px" />
          <el-select v-model="trailStage" class="trail-stage" clearable placeholder="环节" style="width: 110px">
            <el-option v-for="(label, value) in EVIDENCE_STAGE_LABELS" :key="value" :label="label" :value="value" />
          </el-select>
          <el-input v-model="trailSubjectDigest" class="trail-subject-digest" placeholder="主体摘要（64 位小写 hex）" clearable style="width: 240px" />
          <el-date-picker
            v-model="trailRange"
            class="trail-range"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            start-placeholder="起始时间"
            end-placeholder="结束时间"
            style="width: 340px"
            @calendar-change="onTrailPick"
            :disabled-date="limitTrail"
          />
          <el-button type="primary" class="trail-search-btn" :loading="trailLoading" @click="searchTrails(1)">
            {{ EVIDENCE_SEARCH_LABEL }}
          </el-button>
        </div>
        <p class="hint-text">{{ EVIDENCE_WINDOW_HINT_ONLY }}</p>
        <el-alert v-if="trailError" class="list-error" :title="trailError" type="error" :closable="false" show-icon>
          <template #default>
            <el-button link type="primary" class="retry-btn" @click="searchTrails(trailPageNum)">{{ EVIDENCE_RETRY_LABEL }}</el-button>
          </template>
        </el-alert>

        <template v-if="trailData?.mode === 'TRAIL'">
          <el-descriptions :column="3" size="small" border class="trail-record-card">
            <el-descriptions-item label="存证编号">{{ trailData.evidenceRecord?.evidenceNo }}</el-descriptions-item>
            <el-descriptions-item label="环节">
              {{ labelOf(EVIDENCE_STAGE_LABELS, trailData.evidenceRecord?.stageType) }}
            </el-descriptions-item>
            <el-descriptions-item label="业务单据号">{{ trailData.evidenceRecord?.businessNo }}</el-descriptions-item>
            <el-descriptions-item label="操作类型">{{ trailData.evidenceRecord?.operationType }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="EVIDENCE_STATUS_TAG[trailData.evidenceRecord?.status ?? ''] ?? 'info'">
                {{ labelOf(EVIDENCE_STATUS_LABELS, trailData.evidenceRecord?.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="链上标识">{{ orDash(trailData.evidenceRecord?.chainTxId) }}</el-descriptions-item>
            <el-descriptions-item label="内容摘要" :span="3">
              <span
                class="digest-cell digest-copy"
                :title="EVIDENCE_DIGEST_HINT"
                @click="copyDigest(trailData.evidenceRecord?.contentDigest)"
              >{{ truncateDigest(trailData.evidenceRecord?.contentDigest) }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="trailHasUnavailable"
            class="trail-warning"
            :title="EVIDENCE_UNAVAILABLE_WARNING"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-timeline class="trail-timeline">
            <el-timeline-item
              v-for="node in trailData.evidenceActions ?? []"
              :key="`ev-${node.action}-${node.occurredAt}`"
              :timestamp="node.occurredAt"
              type="primary"
            >
              <div class="trail-node">
                <span>{{ labelOf(EVIDENCE_ACTION_LABELS, node.action) }}</span>
                <span class="trail-node-meta">
                  {{ labelOf(EVIDENCE_STATUS_LABELS, node.fromStatus) }} → {{ labelOf(EVIDENCE_STATUS_LABELS, node.toStatus) }}
                </span>
                <span
                  class="trail-node-meta digest-cell digest-copy"
                  :title="EVIDENCE_DIGEST_HINT"
                  @click="copyDigest(node.actorDigest)"
                >{{ truncateDigest(node.actorDigest) }}</span>
                <span v-if="node.detail" class="trail-node-meta">{{ node.detail }}</span>
              </div>
            </el-timeline-item>
            <el-timeline-item
              v-for="node in trailData.businessTrail ?? []"
              :key="`biz-${node.action}-${node.occurredAt}`"
              :timestamp="node.occurredAt"
              :type="node.sourceStatus === 'UNAVAILABLE' ? 'danger' : 'success'"
            >
              <div class="trail-node" :class="{ 'trail-node-unavailable': node.sourceStatus === 'UNAVAILABLE' }">
                <span>{{ labelOf(EVIDENCE_TRAIL_SOURCE_LABELS, node.source) }} · {{ node.action }}</span>
                <span class="trail-node-meta">{{ orDash(node.result) }}</span>
                <span class="trail-node-meta">{{ orDash(node.summary) }}</span>
                <span class="trail-node-meta">{{ EVIDENCE_TRAIL_STATUS_PREFIX }}{{ node.sourceStatus }}</span>
              </div>
            </el-timeline-item>
          </el-timeline>
          <p class="hint-text trail-access-hint">{{ EVIDENCE_ACCESS_HINT }}</p>
        </template>

        <template v-else-if="trailData?.mode === 'LIST'">
          <el-table :data="trailData.list ?? []" class="trail-list-table" size="small">
            <el-table-column prop="evidenceNo" label="存证编号" width="150" />
            <el-table-column label="环节" width="80">
              <template #default="scope">{{ labelOf(EVIDENCE_STAGE_LABELS, scope.row.stageType) }}</template>
            </el-table-column>
            <el-table-column prop="businessNo" label="业务单据号" width="150" show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="scope">
                <el-tag size="small" :type="EVIDENCE_STATUS_TAG[scope.row.status] ?? 'info'">
                  {{ labelOf(EVIDENCE_STATUS_LABELS, scope.row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="occurredAt" label="发生时点" width="180" />
            <template #empty>
              <span class="empty-tip">{{ EVIDENCE_EMPTY_TEXT }}</span>
            </template>
          </el-table>
          <el-pagination
            v-if="(trailData.total ?? 0) > trailPageSize"
            class="trail-pagination"
            layout="total, prev, pager, next"
            :total="trailData.total ?? 0"
            :page-size="trailPageSize"
            :current-page="trailPageNum"
            @current-change="onTrailPageChange"
          />
          <p class="hint-text trail-hint">{{ trailData.trailHint ?? EVIDENCE_TRAIL_HINT_DEFAULT }}</p>
        </template>
      </el-tab-pane>

      <!-- A-5 审计报告：生成表单（≥1 条件引导灰字）+ 结果卡 + 双格式下载 + 按号补取 -->
      <el-tab-pane :label="EVIDENCE_TAB_LABELS.reports" name="audit-reports">
        <div class="filter-bar">
          <el-select v-model="reportStage" class="report-stage" clearable placeholder="环节" style="width: 110px">
            <el-option v-for="(label, value) in EVIDENCE_STAGE_LABELS" :key="value" :label="label" :value="value" />
          </el-select>
          <el-input v-model="reportBusinessNo" class="report-business-no" placeholder="业务单据号" clearable style="width: 170px" />
          <el-input v-model="reportSubjectDigest" class="report-subject-digest" placeholder="主体摘要（64 位小写 hex）" clearable style="width: 240px" />
          <el-date-picker
            v-model="reportRange"
            class="report-range"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            start-placeholder="起始时间"
            end-placeholder="结束时间"
            style="width: 340px"
            @calendar-change="onReportPick"
            :disabled-date="limitReport"
          />
          <el-input v-model="reportRemark" class="report-remark" placeholder="备注（可空）" clearable style="width: 180px" />
          <el-button type="primary" class="report-generate-btn" :loading="reportLoading" @click="generate">
            {{ EVIDENCE_GENERATE_LABEL }}
          </el-button>
        </div>
        <p class="hint-text">{{ EVIDENCE_CONDITION_HINT }}；{{ EVIDENCE_WINDOW_HINT_ONLY }}</p>
        <el-alert v-if="reportError" class="list-error" :title="reportError" type="error" :closable="false" show-icon />

        <el-card v-if="reportResult" class="report-result-card" shadow="never">
          <template #header>
            <span>报告编号：{{ reportResult.reportNo }}</span>
          </template>
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="范围口径">
              <span v-for="(value, key) in reportResult.scope" :key="key" class="scope-item">{{ key }}: {{ value }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="存证条数">{{ reportResult.recordCount }}</el-descriptions-item>
            <el-descriptions-item label="核验结论分布">
              <el-tag
                v-for="(count, key) in reportResult.conclusionSummary"
                :key="key"
                size="small"
                class="conclusion-badge"
                :type="EVIDENCE_VERIFY_CONCLUSION_TAG[String(key)] ?? 'info'"
              >
                {{ labelOf(EVIDENCE_VERIFY_CONCLUSION_LABELS, String(key)) }} {{ count }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="异常条数">{{ reportResult.anomalyCount }}</el-descriptions-item>
            <el-descriptions-item label="内容摘要" :span="2">
              <span
                class="digest-cell digest-copy"
                :title="EVIDENCE_DIGEST_HINT"
                @click="copyDigest(reportResult.contentDigest)"
              >{{ truncateDigest(reportResult.contentDigest) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="生成人">{{ reportResult.generatedBy }}</el-descriptions-item>
            <el-descriptions-item label="生成时点">{{ reportResult.generatedAt }}</el-descriptions-item>
            <el-descriptions-item label="备注" :span="2">{{ orDash(reportResult.remark) }}</el-descriptions-item>
          </el-descriptions>
          <div class="report-download-bar">
            <el-button type="primary" class="report-csv-btn" @click="downloadReport(reportResult.reportNo, 'csv')">
              {{ EVIDENCE_CSV_LABEL }}
            </el-button>
            <el-button class="report-json-btn" @click="downloadReport(reportResult.reportNo, 'json')">
              {{ EVIDENCE_JSON_LABEL }}
            </el-button>
          </div>
        </el-card>

        <div class="filter-bar redownload-bar">
          <span class="hint-text">{{ EVIDENCE_REDOWNLOAD_TITLE }}</span>
          <el-input v-model="redownloadNo" class="report-redownload-no" placeholder="报告编号，如 EV-RPT-20261108-0002" clearable style="width: 240px" />
          <el-button class="report-redownload-csv-btn" @click="submitRedownload('csv')">{{ EVIDENCE_CSV_LABEL }}</el-button>
          <el-button class="report-redownload-json-btn" @click="submitRedownload('json')">{{ EVIDENCE_JSON_LABEL }}</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>

    <EvidenceRecordDrawer v-model:visible="drawerVisible" :evidence-no="drawerEvidenceNo" />
  </div>
</template>

<style scoped>
.page-desc {
  color: #6b7280;
  font-size: 13px;
}

.hint-text {
  color: #9ca3af;
  font-size: 12px;
  margin-right: 10px;
}

.anomaly-cards {
  display: flex;
  gap: 16px;
  margin: 16px 0 8px;
}

.anomaly-card {
  width: 160px;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  cursor: pointer;
  text-align: center;
}

.anomaly-card:hover {
  border-color: var(--el-color-primary);
}

.anomaly-card-num {
  font-size: 24px;
  font-weight: 600;
}

.anomaly-card-label {
  color: #6b7280;
  font-size: 13px;
}

.snapshot-hint {
  color: #9ca3af;
  font-size: 12px;
}

.trail-record-card {
  margin: 12px 0;
}

.trail-warning {
  margin: 10px 0;
}

.trail-timeline {
  margin: 16px 0 0 8px;
}

.trail-node {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.trail-node-meta {
  color: #6b7280;
  font-size: 12px;
}

.trail-node-unavailable,
.trail-node-unavailable .trail-node-meta {
  color: var(--el-color-danger);
}

.trail-access-hint,
.trail-hint {
  margin-top: 10px;
}

.digest-cell {
  font-family: monospace;
  word-break: break-all;
  font-size: 12px;
}

.digest-copy {
  cursor: pointer;
}

.digest-copy:hover {
  color: var(--el-color-primary);
}

.scope-item {
  margin-right: 10px;
}

.conclusion-badge {
  margin-right: 8px;
}

.report-download-bar {
  margin-top: 12px;
}

.redownload-bar {
  margin-top: 16px;
}
</style>
