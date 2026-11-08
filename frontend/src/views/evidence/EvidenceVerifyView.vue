<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getRecordDetail, verifyRecord, type EvidenceDetailView, type EvidenceVerifyView } from '../../api/evidence'
import {
  EVIDENCE_ACTION_LABELS,
  EVIDENCE_COPY_FAIL_TIP,
  EVIDENCE_COPY_SUCCESS_TIP,
  EVIDENCE_DIGEST_HINT,
  EVIDENCE_EMPTY_TEXT,
  EVIDENCE_NO_INPUT_TIP,
  EVIDENCE_NOT_ON_CHAIN_FAILED_PREFIX,
  EVIDENCE_NOT_ON_CHAIN_PENDING_TIP,
  EVIDENCE_NOT_ON_CHAIN_STATUS_PREFIX,
  EVIDENCE_STATUS_LABELS,
  EVIDENCE_VERIFY_CONCLUSION_LABELS,
  EVIDENCE_VERIFY_PAGE_DESC,
  EVIDENCE_VERIFY_PAGE_TITLE,
  EVIDENCE_VERIFY_SUBMIT_LABEL,
  EVIDENCE_VERIFY_TITLE_PREFIX,
  labelOf,
  orDash,
  truncateDigest,
} from '../../constants/evidence'

/**
 * 存证核验工具单页（WBS-3.8.5 hifi §4；lofi 屏 B）：单卡流（Q2-A）——
 * 输入存证编号 → 发起核验 → 三态结论卡 + 核验留痕表（detail 端点 actions，核验后同步刷新）。
 * NOT_ON_CHAIN 文案由 verify 出参 status 驱动（FAILED → 上链失败 + 原因摘要取 detail；
 * PENDING → 待上链）。核验为唯一动作（只读比对），页面零写按钮（S2-9）。
 */
const evidenceNo = ref('')
const verifyLoading = ref(false)
const result = ref<EvidenceVerifyView | null>(null)
const trail = ref<EvidenceDetailView | null>(null)

async function submit() {
  const no = evidenceNo.value.trim()
  if (!no) {
    ElMessage.warning(EVIDENCE_NO_INPUT_TIP)
    return
  }
  verifyLoading.value = true
  try {
    result.value = await verifyRecord(no)
    trail.value = await getRecordDetail(no)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    verifyLoading.value = false
  }
}

/** NOT_ON_CHAIN 当前状态提示（status 驱动，P1 实测口径）。 */
function notOnChainTip(): string {
  if (!result.value) return ''
  if (result.value.status === 'PENDING') return EVIDENCE_NOT_ON_CHAIN_PENDING_TIP
  if (result.value.status === 'FAILED') {
    const reason = trail.value?.record.lastErrorSummary
    return reason ? `${EVIDENCE_NOT_ON_CHAIN_FAILED_PREFIX}（${reason}）` : EVIDENCE_NOT_ON_CHAIN_FAILED_PREFIX
  }
  return `${EVIDENCE_NOT_ON_CHAIN_STATUS_PREFIX}${labelOf(EVIDENCE_STATUS_LABELS, result.value.status)}`
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
</script>

<template>
  <div class="evidence-verify-page">
    <h2 class="page-title">{{ EVIDENCE_VERIFY_PAGE_TITLE }}</h2>
    <p class="page-desc">{{ EVIDENCE_VERIFY_PAGE_DESC }}</p>

    <div class="filter-bar">
      <el-input
        v-model="evidenceNo"
        class="verify-no"
        placeholder="存证编号，如 EV20261107000004"
        clearable
        style="width: 260px"
        @keyup.enter="submit"
      />
      <el-button type="primary" class="verify-submit-btn" :loading="verifyLoading" @click="submit">
        {{ EVIDENCE_VERIFY_SUBMIT_LABEL }}
      </el-button>
    </div>

    <!-- 三态结论卡（B-2）：PASS 绿 / MISMATCH 红 / NOT_ON_CHAIN 橙；摘要中截断 + 点击复制（hifi §4） -->
    <el-card v-if="result?.conclusion === 'PASS'" class="verify-card-pass" shadow="never">
      <template #header>
        <span class="verify-title-pass">{{ EVIDENCE_VERIFY_TITLE_PREFIX }}{{ labelOf(EVIDENCE_VERIFY_CONCLUSION_LABELS, result.conclusion) }}</span>
      </template>
      <el-descriptions :column="2" size="small" border>
        <el-descriptions-item label="存证编号">{{ result.evidenceNo }}</el-descriptions-item>
        <el-descriptions-item label="业务单据号">{{ result.businessNo }}</el-descriptions-item>
        <el-descriptions-item label="链上交易标识">{{ orDash(result.chainTxId) }}</el-descriptions-item>
        <el-descriptions-item label="块号">{{ orDash(trail?.record.chainBlockNo) }}</el-descriptions-item>
        <el-descriptions-item label="核验人">{{ result.verifiedBy }}</el-descriptions-item>
        <el-descriptions-item label="核验时点">{{ result.verifiedAt }}</el-descriptions-item>
        <el-descriptions-item label="期望摘要" :span="2">
          <span class="digest-cell digest-copy" :title="EVIDENCE_DIGEST_HINT" @click="copyDigest(result.expectedDigest)">{{ truncateDigest(result.expectedDigest) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="实际摘要" :span="2">
          <span class="digest-cell digest-copy" :title="EVIDENCE_DIGEST_HINT" @click="copyDigest(result.actualDigest)">{{ truncateDigest(result.actualDigest) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="本地摘要" :span="2">
          <span class="digest-cell digest-copy" :title="EVIDENCE_DIGEST_HINT" @click="copyDigest(result.localDigest)">{{ truncateDigest(result.localDigest) }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card v-else-if="result?.conclusion === 'MISMATCH'" class="verify-card-mismatch" shadow="never">
      <template #header>
        <span class="verify-title-mismatch">{{ EVIDENCE_VERIFY_TITLE_PREFIX }}{{ labelOf(EVIDENCE_VERIFY_CONCLUSION_LABELS, result.conclusion) }}</span>
      </template>
      <el-descriptions :column="2" size="small" border>
        <el-descriptions-item label="存证编号">{{ result.evidenceNo }}</el-descriptions-item>
        <el-descriptions-item label="业务单据号">{{ result.businessNo }}</el-descriptions-item>
        <el-descriptions-item label="核验时点" :span="2">{{ result.verifiedAt }}</el-descriptions-item>
        <el-descriptions-item label="期望摘要" :span="2">
          <span class="digest-cell digest-copy" :title="EVIDENCE_DIGEST_HINT" @click="copyDigest(result.expectedDigest)">{{ truncateDigest(result.expectedDigest) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="实际摘要" :span="2">
          <span class="digest-cell digest-copy" :title="EVIDENCE_DIGEST_HINT" @click="copyDigest(result.actualDigest)">{{ truncateDigest(result.actualDigest) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="本地摘要" :span="2">
          <span class="digest-cell digest-copy" :title="EVIDENCE_DIGEST_HINT" @click="copyDigest(result.localDigest)">{{ truncateDigest(result.localDigest) }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card v-else-if="result?.conclusion === 'NOT_ON_CHAIN'" class="verify-card-not-on-chain" shadow="never">
      <template #header>
        <span class="verify-title-not-on-chain">{{ EVIDENCE_VERIFY_TITLE_PREFIX }}{{ labelOf(EVIDENCE_VERIFY_CONCLUSION_LABELS, result.conclusion) }}</span>
      </template>
      <p class="not-on-chain-tip">{{ notOnChainTip() }}</p>
      <el-descriptions :column="2" size="small" border>
        <el-descriptions-item label="存证编号">{{ result.evidenceNo }}</el-descriptions-item>
        <el-descriptions-item label="业务单据号">{{ result.businessNo }}</el-descriptions-item>
        <el-descriptions-item label="核验人">{{ result.verifiedBy }}</el-descriptions-item>
        <el-descriptions-item label="核验时点">{{ result.verifiedAt }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 核验留痕表（B-3）：detail 端点 actions 只读渲染（治理档操作人原文，Q3） -->
    <template v-if="trail">
      <h4 class="verify-trail-title">状态流转与核验留痕</h4>
      <el-table :data="trail.actions" size="small" class="verify-trail-table">
        <el-table-column label="动作" width="90">
          <template #default="scope">{{ labelOf(EVIDENCE_ACTION_LABELS, scope.row.action) }}</template>
        </el-table-column>
        <el-table-column label="状态迁移" width="140">
          <template #default="scope">
            {{ labelOf(EVIDENCE_STATUS_LABELS, scope.row.fromStatus) }} → {{ labelOf(EVIDENCE_STATUS_LABELS, scope.row.toStatus) }}
          </template>
        </el-table-column>
        <el-table-column label="操作人" min-width="150" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.actorSubjectNo }}</template>
        </el-table-column>
        <el-table-column label="结论" width="80">
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
</template>

<style scoped>
.verify-card-pass,
.verify-card-mismatch,
.verify-card-not-on-chain {
  margin-top: 14px;
}

.verify-title-pass {
  color: var(--el-color-success);
  font-weight: 600;
}

.verify-title-mismatch {
  color: var(--el-color-danger);
  font-weight: 600;
}

.verify-title-not-on-chain {
  color: var(--el-color-warning);
  font-weight: 600;
}

.not-on-chain-tip {
  margin: 0 0 10px;
  color: var(--el-color-warning);
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

.verify-trail-title {
  margin: 16px 0 8px;
  color: #374151;
}

.page-desc {
  color: #6b7280;
  font-size: 13px;
}
</style>
