<script setup lang="ts">
import { ref, watch } from 'vue'
import { getRecordDetail, type EvidenceDetailView } from '../../../api/evidence'
import {
  EVIDENCE_ACTION_LABELS,
  EVIDENCE_EMPTY_TEXT,
  EVIDENCE_STAGE_LABELS,
  EVIDENCE_STATUS_LABELS,
  EVIDENCE_STATUS_TAG,
  labelOf,
  orDash,
} from '../../../constants/evidence'

/**
 * 存证详情抽屉（WBS-3.8.5 hifi §4；lofi W6）：props { evidenceNo, visible } + emit update:visible。
 * 打开时自取 detail；只读呈现六要素 + 状态流转与核验留痕（治理面 actorSubjectNo 原文，Q3）。
 * 零动作按钮（核验入口唯核验工具页，Q2-A 单一入口；B5-3 只读铁律）。
 */
const props = defineProps<{ evidenceNo: string; visible: boolean }>()
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void }>()

const detail = ref<EvidenceDetailView | null>(null)
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    detail.value = await getRecordDetail(props.evidenceNo)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

watch(
  () => props.visible,
  (visible) => {
    if (visible) void load()
  },
)

function statusText(value: string | null): string {
  return labelOf(EVIDENCE_STATUS_LABELS, value)
}
</script>

<template>
  <el-drawer
    :model-value="visible"
    size="480"
    :title="`存证详情 ${evidenceNo}`"
    @update:model-value="emit('update:visible', $event)"
  >
    <el-alert v-if="error" class="list-error" :title="error" type="error" :closable="false" show-icon />
    <div v-loading="loading">
      <template v-if="detail">
        <h4 class="drawer-section">基本信息</h4>
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="存证编号">{{ detail.record.evidenceNo }}</el-descriptions-item>
          <el-descriptions-item label="环节">
            {{ labelOf(EVIDENCE_STAGE_LABELS, detail.record.stageType) }}
          </el-descriptions-item>
          <el-descriptions-item label="业务单据号">{{ detail.record.businessNo }}</el-descriptions-item>
          <el-descriptions-item label="操作类型">{{ detail.record.operationType }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="EVIDENCE_STATUS_TAG[detail.record.status] ?? 'info'">
              {{ statusText(detail.record.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="发生时点">{{ orDash(detail.record.occurredAt) }}</el-descriptions-item>
          <el-descriptions-item label="主体（DID 摘要）" :span="2">
            <span class="digest-cell" :title="detail.record.subjectDidDigest">{{ detail.record.subjectDidDigest }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="内容摘要" :span="2">
            <span class="digest-cell" :title="detail.record.contentDigest">{{ detail.record.contentDigest }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="链上交易标识">{{ orDash(detail.record.chainTxId) }}</el-descriptions-item>
          <el-descriptions-item label="块号">{{ orDash(detail.record.chainBlockNo) }}</el-descriptions-item>
          <el-descriptions-item label="失败原因" :span="2">{{ orDash(detail.record.lastErrorSummary) }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="drawer-section">状态流转与核验留痕</h4>
        <el-table :data="detail.actions" size="small" class="drawer-action-table">
          <el-table-column label="动作" width="90">
            <template #default="scope">{{ labelOf(EVIDENCE_ACTION_LABELS, scope.row.action) }}</template>
          </el-table-column>
          <el-table-column label="状态迁移" width="130">
            <template #default="scope">
              {{ statusText(scope.row.fromStatus) }} → {{ statusText(scope.row.toStatus) }}
            </template>
          </el-table-column>
          <el-table-column label="操作人" min-width="150" show-overflow-tooltip>
            <template #default="scope">{{ scope.row.actorSubjectNo }}</template>
          </el-table-column>
          <el-table-column label="结论" width="70">
            <template #default="scope">{{ orDash(scope.row.detail) }}</template>
          </el-table-column>
          <el-table-column label="原因码" width="90">
            <template #default="scope">{{ orDash(scope.row.reasonCode) }}</template>
          </el-table-column>
          <el-table-column label="时点" width="170">
            <template #default="scope">{{ scope.row.createdAt }}</template>
          </el-table-column>
          <template #empty>
            <span class="empty-tip">{{ EVIDENCE_EMPTY_TEXT }}</span>
          </template>
        </el-table>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-section {
  margin: 14px 0 8px;
  color: #374151;
}

.digest-cell {
  font-family: monospace;
  word-break: break-all;
  font-size: 12px;
}
</style>
