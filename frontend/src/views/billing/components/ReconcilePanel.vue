<template>
  <div class="reconcile-panel">
    <div class="filter-bar">
      <el-input v-model="contractNo" class="reconcile-contract" placeholder="合约号（CO000001）" style="width: 200px" />
      <el-date-picker
        v-model="period"
        class="reconcile-period"
        type="month"
        value-format="YYYY-MM"
        placeholder="账期"
        style="width: 160px"
      />
      <el-button type="primary" class="run-reconcile-btn" :loading="running" @click="run">执行对账</el-button>
    </div>

    <el-alert
      v-if="errorTip"
      class="reconcile-error"
      :title="errorTip"
      type="error"
      :closable="false"
      show-icon
    />

    <template v-if="current">
      <el-alert
        v-if="current.conclusion === 'DIFFERENCE'"
        class="difference-alert"
        :title="RECONCILE_DIFFERENCE_HINT"
        type="error"
        :closable="false"
        show-icon
      />

      <el-card class="conclusion-card" shadow="never">
        <div class="conclusion-head">
          <el-tag class="conclusion-tag" :type="CONCLUSION_TAG[current.conclusion] ?? 'info'">
            {{ labelOf(CONCLUSION_LABELS, current.conclusion) }}
          </el-tag>
          <span class="record-operator">执行人：{{ current.createdBy ?? '—' }}</span>
          <span class="record-time">执行时点：{{ current.createdAt }}</span>
          <el-button link type="primary" class="view-bills-btn" @click="viewBills">查看该期账单</el-button>
        </div>
        <el-descriptions :column="2" border class="figures-table">
          <el-descriptions-item v-for="key in FIGURE_KEYS" :key="key" :label="RECONCILE_FIGURE_LABELS[key]">
            {{ figureOf(key) }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <section class="differences">
        <h4 class="section-title">差异明细</h4>
        <el-table :data="current.differences" class="differences-table" size="small">
          <el-table-column label="类型" width="230">
            <template #default="scope">
              {{ labelOf(DIFFERENCE_TYPE_LABELS, scope.row.type) }}
            </template>
          </el-table-column>
          <el-table-column label="腿" width="200">
            <template #default="scope">{{ scope.row.leg ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="expected" label="期望值" width="110" />
          <el-table-column prop="actual" label="实际值" width="110" />
          <el-table-column label="说明" min-width="200">
            <template #default="scope">{{ scope.row.detail ?? '—' }}</template>
          </el-table-column>
          <template #empty>
            <span class="empty-tip">{{ DIFFERENCE_EMPTY_TEXT }}</span>
          </template>
        </el-table>
      </section>
    </template>

    <section class="records">
      <h4 class="section-title">对账记录</h4>
      <el-table
        :data="records"
        class="record-table"
        size="small"
        row-class-name="record-row"
        @row-click="onRecordClick"
      >
        <el-table-column prop="createdAt" label="执行时点" width="180" />
        <el-table-column label="结论" width="100">
          <template #default="scope">
            <el-tag :type="CONCLUSION_TAG[scope.row.conclusion] ?? 'info'">
              {{ labelOf(CONCLUSION_LABELS, scope.row.conclusion) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="差异数" width="90">
          <template #default="scope">{{ scope.row.differences.length }}</template>
        </el-table-column>
        <el-table-column label="执行人" min-width="160">
          <template #default="scope">{{ scope.row.createdBy ?? '—' }}</template>
        </el-table-column>
        <template #empty>
          <span class="empty-tip">暂无对账记录</span>
        </template>
      </el-table>
      <el-pagination
        v-if="recordTotal > recordPageSize"
        class="record-pagination"
        layout="total, sizes, prev, pager, next"
        :total="recordTotal"
        :page-size="recordPageSize"
        :page-sizes="PAGE_SIZES"
        :current-page="recordPageNum"
        @current-change="onRecordPageChange"
        @size-change="onRecordSizeChange"
      />
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import {
  listReconciliations,
  runReconciliation,
  type Reconciliation,
} from '../../../api/billing'
import { labelOf } from '../../../constants/catalog'
import {
  CONCLUSION_LABELS,
  CONCLUSION_TAG,
  CONTRACT_REQUIRED_TIP,
  DIFFERENCE_EMPTY_TEXT,
  DIFFERENCE_TYPE_LABELS,
  PERIOD_REQUIRED_TIP,
  RECONCILE_DIFFERENCE_HINT,
  RECONCILE_FIGURE_LABELS,
} from '../../../constants/billing'

/**
 * 对账工作台（WBS-3.7.7 hifi §3 P6 / §6）：
 * B-W4 执行对账 → 结论卡（四方 / 八方数值）+ 七类差异明细 + 冻结提示（fail-closed 口径）；
 * B-R4 记录列表分页 + 点击记录回填结论卡；界面**零修数入口**（B11-3 / B11-4）。
 * 前端仅做合约号 / 账期空值拦截；结论与差异判定一律以服务端为准。
 */
const emit = defineEmits<{ 'view-bills': [payload: { contractNo: string; period: string }] }>()

const FIGURE_KEYS = [
  'counterUsedCount',
  'usageLogAllowedCount',
  'usageLogAllowedAllTime',
  'meteringFlowCount',
  'meteringFlowAllTime',
  'billQuantity',
  'billTotalAmount',
  'recomputedAmount',
]

const contractNo = ref('')
const period = ref<string | null>(null)
const running = ref(false)
const errorTip = ref('')
const current = ref<Reconciliation | null>(null)
const records = ref<Reconciliation[]>([])
const recordTotal = ref(0)
const recordPageNum = ref(1)
const recordPageSize = ref(10)
/** 分页档位（hifi §3 P1 口径：10 / 20 / 50）。 */
const PAGE_SIZES = [10, 20, 50]

function figureOf(key: string): string | number {
  const value = (current.value as unknown as Record<string, unknown> | null)?.[key]
  return value === null || value === undefined ? '—' : (value as string | number)
}

async function run(): Promise<void> {
  if (!contractNo.value.trim()) {
    errorTip.value = CONTRACT_REQUIRED_TIP
    return
  }
  if (!period.value) {
    errorTip.value = PERIOD_REQUIRED_TIP
    return
  }
  running.value = true
  errorTip.value = ''
  try {
    current.value = await runReconciliation(contractNo.value.trim(), period.value)
    recordPageNum.value = 1
  } catch (error) {
    current.value = null
    errorTip.value = error instanceof Error ? error.message : String(error)
  } finally {
    running.value = false
    await loadRecords()
  }
}

async function loadRecords(): Promise<void> {
  if (!contractNo.value.trim()) return
  try {
    const page = await listReconciliations(contractNo.value.trim(), {
      pageNum: recordPageNum.value,
      pageSize: recordPageSize.value,
      period: period.value ?? undefined,
    })
    records.value = page.list
    recordTotal.value = page.total
  } catch (error) {
    records.value = []
    recordTotal.value = 0
    if (!errorTip.value) {
      errorTip.value = error instanceof Error ? error.message : String(error)
    }
  }
}

function onRecordClick(row: Reconciliation): void {
  current.value = row
}

function onRecordPageChange(page: number): void {
  recordPageNum.value = page
  void loadRecords()
}

function onRecordSizeChange(size: number): void {
  recordPageSize.value = size
  recordPageNum.value = 1
  void loadRecords()
}

function viewBills(): void {
  emit('view-bills', { contractNo: contractNo.value.trim(), period: period.value ?? '' })
}
</script>

<style scoped>
.reconcile-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.filter-bar {
  display: flex;
  gap: 8px;
}

.conclusion-head {
  display: flex;
  gap: 14px;
  align-items: center;
  margin-bottom: 12px;
  font-size: 13px;
}

.section-title {
  margin: 4px 0 8px;
  font-size: 14px;
}

.empty-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.record-pagination {
  margin-top: 8px;
  justify-content: flex-end;
}
</style>
