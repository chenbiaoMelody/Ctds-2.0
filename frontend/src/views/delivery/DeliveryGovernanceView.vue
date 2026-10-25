<template>
  <div class="governance-page">
    <el-alert :title="GOVERNANCE_READ_TRACE_HINT" type="info" :closable="false" class="trace-hint" />
    <el-tabs v-model="activeTab">
      <!-- Tab1 导出审批：沙箱治理 -->
      <el-tab-pane label="导出审批" name="sandbox">
        <div class="filter-bar">
          <el-input v-model="sandboxFilters.contractNo" clearable placeholder="合约编号" class="contract-input" />
          <el-select v-model="sandboxFilters.status" clearable placeholder="状态" class="type-select">
            <el-option
              v-for="opt in SANDBOX_STATUS_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <el-button type="primary" @click="searchSandboxes">查询</el-button>
          <el-button @click="resetSandboxFilter">重置</el-button>
        </div>
        <el-table v-loading="sandboxLoading" :data="sandboxList" empty-text="暂无沙箱任务">
          <el-table-column prop="sandboxNo" label="任务编号" width="120" />
          <el-table-column prop="contractNo" label="合约编号" width="110" />
          <el-table-column prop="productName" label="产品名称" min-width="130" />
          <el-table-column prop="requesterNo" label="需求方" width="145" />
          <el-table-column label="状态" width="90">
            <template #default="scope">
              <el-tag :type="SANDBOX_STATUS_TAG_TYPES[scope.row.status] ?? 'info'">
                {{ SANDBOX_STATUS_LABELS[scope.row.status] ?? scope.row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="开通时间" width="165" />
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="scope">
              <el-button link type="primary" @click="openSandboxDetail(scope.row.sandboxNo)">
                治理详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="sandboxTotal > pageSize"
          layout="total, prev, pager, next"
          :total="sandboxTotal"
          :page-size="pageSize"
          :current-page="sandboxPageNum"
          @current-change="onSandboxPageChange"
        />
      </el-tab-pane>

      <!-- Tab2 交付记录治理 -->
      <el-tab-pane label="交付记录治理" name="delivery">
        <div class="filter-bar">
          <el-input v-model="deliveryFilters.contractNo" clearable placeholder="合约编号" class="contract-input" />
          <el-input v-model="deliveryFilters.deliveryNo" clearable placeholder="交付编号" class="contract-input" />
          <el-button type="primary" @click="searchDeliveries">查询</el-button>
          <el-button @click="resetDeliveryFilter">重置</el-button>
        </div>
        <el-table v-loading="deliveryLoading" :data="deliveryList" empty-text="暂无交付记录">
          <el-table-column prop="deliveryNo" label="交付编号" width="120" />
          <el-table-column prop="contractNo" label="合约编号" width="110" />
          <el-table-column prop="productName" label="产品名称" min-width="130" />
          <el-table-column label="形态" width="100">
            <template #default="scope">
              <el-tag :type="DELIVERY_TYPE_TAG_TYPES[scope.row.deliveryType] ?? 'info'">
                {{ DELIVERY_TYPE_LABELS[scope.row.deliveryType] ?? scope.row.deliveryType }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="requesterNo" label="发起方" width="145" />
          <el-table-column prop="createdAt" label="时间" width="165" />
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="scope">
              <el-button link type="primary" @click="openDeliveryDetail(scope.row.deliveryNo)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="deliveryTotal > pageSize"
          layout="total, prev, pager, next"
          :total="deliveryTotal"
          :page-size="pageSize"
          :current-page="deliveryPageNum"
          @current-change="onDeliveryPageChange"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- 沙箱治理详情抽屉 -->
    <el-drawer v-model="detailVisible" title="沙箱治理详情" size="640px">
      <template v-if="detailTask">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="任务编号">{{ detailTask.sandboxNo }}</el-descriptions-item>
          <el-descriptions-item label="合约编号">{{ detailTask.contractNo }}</el-descriptions-item>
          <el-descriptions-item label="产品名称">{{ detailTask.productName }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="SANDBOX_STATUS_TAG_TYPES[detailTask.status] ?? 'info'">
              {{ SANDBOX_STATUS_LABELS[detailTask.status] ?? detailTask.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="需求方">{{ detailTask.requesterNo }}</el-descriptions-item>
          <el-descriptions-item label="提供方">{{ detailTask.providerNo }}</el-descriptions-item>
          <el-descriptions-item :label="SANDBOX_QUOTA_COLUMN_LABEL">
            {{ detailTask.cpus }}C·{{ detailTask.memoryMb }}MB·{{ detailTask.ttlSeconds }}S
          </el-descriptions-item>
          <el-descriptions-item label="数据文件数">{{ detailTask.dataFileCount }}</el-descriptions-item>
          <el-descriptions-item label="到期时间">{{ detailTask.expiresAt ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="关闭原因">{{ detailTask.closeReason ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="开通时间" :span="2">{{ detailTask.createdAt }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">结果物</h4>
        <el-table :data="detailResults" empty-text="暂无结果物" size="small">
          <el-table-column prop="fileName" label="文件名" min-width="130" />
          <el-table-column label="大小" width="85">
            <template #default="scope">{{ humanSize(scope.row.sizeBytes) }}</template>
          </el-table-column>
          <el-table-column label="SM3" width="140">
            <template #default="scope">
              <span class="checksum-text">{{ scope.row.checksum ?? '—（超大文件不提供摘要）' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="生成时间" width="160" />
          <el-table-column label="状态" width="90">
            <template #default="scope">
              <el-tag :type="RESULT_STATUS_TAG_TYPES[scope.row.status ?? ''] ?? 'info'">
                {{ RESULT_STATUS_LABELS[scope.row.status ?? ''] ?? scope.row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="结论" width="75">
            <template #default="scope">
              {{ scope.row.conclusion ? REVIEW_CONCLUSION_LABELS[scope.row.conclusion] : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="驳回理由" min-width="140">
            <template #default="scope">{{ scope.row.rejectReason ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="轮次" width="65">
            <template #default="scope">{{ scope.row.reviewRound ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="scope">
              <el-tooltip
                v-if="scope.row.status !== 'PENDING'"
                :content="REVIEW_CONCLUDED_ROW_HINT"
                placement="top"
              >
                <el-button link type="primary" disabled>审核</el-button>
              </el-tooltip>
              <el-button v-else link type="primary" @click="openReview(scope.row.fileName)">审核</el-button>
            </template>
          </el-table-column>
        </el-table>

        <h4 class="section-title">审核留痕</h4>
        <el-table :data="reviews" empty-text="暂无审核留痕" size="small">
          <el-table-column prop="fileName" label="文件名" min-width="130" />
          <el-table-column label="轮次" width="65">
            <template #default="scope">{{ scope.row.reviewRound }}</template>
          </el-table-column>
          <el-table-column label="结论" width="75">
            <template #default="scope">{{ REVIEW_CONCLUSION_LABELS[scope.row.conclusion] }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="理由" min-width="140">
            <template #default="scope">{{ scope.row.reason ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="reviewedBy" label="审核人" width="140" />
          <el-table-column prop="createdAt" label="时间" width="160" />
        </el-table>
        <el-pagination
          v-if="reviewsTotal > reviewsPageSize"
          layout="total, prev, pager, next"
          :total="reviewsTotal"
          :page-size="reviewsPageSize"
          :current-page="reviewsPageNum"
          @current-change="onReviewsPageChange"
        />

        <h4 class="section-title">动作留痕（G7）</h4>
        <el-table :data="actionLogs" empty-text="暂无留痕" size="small">
          <el-table-column prop="operatorNo" label="操作者" width="140" />
          <el-table-column label="动作" width="110">
            <template #default="scope">
              {{ ACTION_LOG_ACTION_LABELS[scope.row.action] ?? scope.row.action }}
            </template>
          </el-table-column>
          <el-table-column label="结果" width="70">
            <template #default="scope">
              {{ ACTION_LOG_RESULT_LABELS[scope.row.result] ?? scope.row.result }}
            </template>
          </el-table-column>
          <el-table-column prop="reasonCode" label="结果码" width="100">
            <template #default="scope">{{ scope.row.reasonCode ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="deliveryNo" label="交付编号" width="105">
            <template #default="scope">{{ scope.row.deliveryNo ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" label="时间" width="160" />
        </el-table>
        <el-pagination
          v-if="actionLogsTotal > actionLogsPageSize"
          layout="total, prev, pager, next"
          :total="actionLogsTotal"
          :page-size="actionLogsPageSize"
          :current-page="actionLogsPageNum"
          @current-change="onActionLogsPageChange"
        />
      </template>
    </el-drawer>

    <!-- 交付治理详情抽屉（无下载按钮——治理面不领取加密包） -->
    <el-drawer v-model="deliveryDetailVisible" title="交付治理详情" size="480px">
      <el-descriptions v-if="deliveryDetail" :column="1" border>
        <el-descriptions-item label="交付编号">{{ deliveryDetail.deliveryNo }}</el-descriptions-item>
        <el-descriptions-item label="合约编号">{{ deliveryDetail.contractNo }}</el-descriptions-item>
        <el-descriptions-item label="产品名称">{{ deliveryDetail.productName }}</el-descriptions-item>
        <el-descriptions-item label="形态">
          {{ DELIVERY_TYPE_LABELS[deliveryDetail.deliveryType] ?? deliveryDetail.deliveryType }}
        </el-descriptions-item>
        <el-descriptions-item label="发起方">{{ deliveryDetail.requesterNo }}</el-descriptions-item>
        <el-descriptions-item label="提供方">{{ deliveryDetail.providerNo }}</el-descriptions-item>
        <el-descriptions-item label="文件标识">{{ deliveryDetail.fileId ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="文件名">{{ deliveryDetail.fileName ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="大小">{{ humanSize(deliveryDetail.fileSize) }}</el-descriptions-item>
        <el-descriptions-item label="SM3 校验值">
          <span class="checksum-text">{{ deliveryDetail.checksum ?? '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="结果">{{ deliveryDetail.result }}</el-descriptions-item>
        <el-descriptions-item label="时间">{{ deliveryDetail.createdAt }}</el-descriptions-item>
      </el-descriptions>
    </el-drawer>

    <!-- 审核弹窗 -->
    <el-dialog v-model="reviewVisible" title="结果物审核" width="520px" destroy-on-close>
      <el-alert :title="REVIEW_IMMUTABLE_HINT" type="warning" :closable="false" class="form-hint" />
      <el-form label-width="90px">
        <el-form-item label="结论" required>
          <el-radio-group v-model="reviewForm.conclusion">
            <el-radio v-for="opt in REVIEW_CONCLUSION_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="reviewForm.conclusion === 'REJECT'" label="驳回理由" required>
          <el-input
            v-model="reviewForm.reason"
            type="textarea"
            :rows="3"
            maxlength="256"
            show-word-limit
            placeholder="驳回理由（必填，不超过 256 字）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="reviewSubmitting" @click="submitReview">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  GOVERNANCE_READ_TRACE_HINT,
  REVIEW_IMMUTABLE_HINT,
  REVIEW_CONCLUDED_ROW_HINT,
  SANDBOX_STATUS_LABELS,
  SANDBOX_STATUS_TAG_TYPES,
  SANDBOX_STATUS_OPTIONS,
  SANDBOX_QUOTA_COLUMN_LABEL,
  RESULT_STATUS_LABELS,
  RESULT_STATUS_TAG_TYPES,
  REVIEW_CONCLUSION_LABELS,
  REVIEW_CONCLUSION_OPTIONS,
  DELIVERY_TYPE_LABELS,
  DELIVERY_TYPE_TAG_TYPES,
  ACTION_LOG_ACTION_LABELS,
  ACTION_LOG_RESULT_LABELS,
} from '../../constants/delivery'
import {
  listGovernanceSandboxes,
  getGovernanceSandbox,
  listGovernanceSandboxResults,
  listGovernanceReviews,
  reviewGovernanceResult,
  listGovernanceDeliveries,
  getGovernanceDelivery,
  listGovernanceDeliveryActionLogs,
  type SandboxRecord,
  type SandboxResultItem,
  type ReviewRecord,
  type DeliveryRecord,
  type ActionLogItem,
} from '../../api/delivery'
import { getDemoSubject } from '../../api/client'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../../constants/space'

/**
 * WBS-3.5.6「交付治理」页（hifi §6.4 P4，admin）：Tab1 导出审批（沙箱治理列表 → 治理详情 =
 * 基本信息 + 结果物 + 审核弹窗〔结论不可改 / 驳回理由必填 ≤256〕+ 审核留痕〔轮次〕+
 * 动作留痕〔G7 治理路径〕）；Tab2 交付记录治理（列表 + 详情抽屉，无下载按钮——治理面
 * 不领取加密包）。读即留痕提示常驻。
 */
const router = useRouter()
const activeTab = ref('sandbox')
const pageSize = 10

const sandboxLoading = ref(false)
const sandboxList = ref<SandboxRecord[]>([])
const sandboxTotal = ref(0)
const sandboxPageNum = ref(1)
const sandboxFilters = reactive({ contractNo: '', status: '' })

const deliveryLoading = ref(false)
const deliveryList = ref<DeliveryRecord[]>([])
const deliveryTotal = ref(0)
const deliveryPageNum = ref(1)
const deliveryFilters = reactive({ contractNo: '', deliveryNo: '' })

const detailVisible = ref(false)
const detailTask = ref<SandboxRecord | null>(null)
const detailResults = ref<SandboxResultItem[]>([])

const reviews = ref<ReviewRecord[]>([])
const reviewsTotal = ref(0)
const reviewsPageNum = ref(1)
const reviewsPageSize = 10

const actionLogs = ref<ActionLogItem[]>([])
const actionLogsTotal = ref(0)
const actionLogsPageNum = ref(1)
const actionLogsPageSize = 10

const deliveryDetailVisible = ref(false)
const deliveryDetail = ref<DeliveryRecord | null>(null)

const reviewVisible = ref(false)
const reviewSubmitting = ref(false)
const reviewForm = reactive({ fileName: '', conclusion: 'APPROVE' as 'APPROVE' | 'REJECT', reason: '' })

function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error(error instanceof Error ? error.message : String(error))
}

function humanSize(size: number | null): string {
  if (size == null) return '—'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(2)} MB`
}

async function loadSandboxes(): Promise<void> {
  sandboxLoading.value = true
  try {
    const data = await listGovernanceSandboxes({
      pageNum: sandboxPageNum.value,
      pageSize,
      ...(sandboxFilters.contractNo ? { contractNo: sandboxFilters.contractNo } : {}),
      ...(sandboxFilters.status ? { status: sandboxFilters.status } : {}),
    })
    sandboxList.value = data.list
    sandboxTotal.value = data.total
  } catch (error) {
    showError(error)
  } finally {
    sandboxLoading.value = false
  }
}

function searchSandboxes(): void {
  sandboxPageNum.value = 1
  void loadSandboxes()
}

function resetSandboxFilter(): void {
  sandboxFilters.contractNo = ''
  sandboxFilters.status = ''
  searchSandboxes()
}

function onSandboxPageChange(page: number): void {
  sandboxPageNum.value = page
  void loadSandboxes()
}

async function loadDeliveries(): Promise<void> {
  deliveryLoading.value = true
  try {
    const data = await listGovernanceDeliveries({
      pageNum: deliveryPageNum.value,
      pageSize,
      ...(deliveryFilters.contractNo ? { contractNo: deliveryFilters.contractNo } : {}),
      ...(deliveryFilters.deliveryNo ? { deliveryNo: deliveryFilters.deliveryNo } : {}),
    })
    deliveryList.value = data.list
    deliveryTotal.value = data.total
  } catch (error) {
    showError(error)
  } finally {
    deliveryLoading.value = false
  }
}

function searchDeliveries(): void {
  deliveryPageNum.value = 1
  void loadDeliveries()
}

function resetDeliveryFilter(): void {
  deliveryFilters.contractNo = ''
  deliveryFilters.deliveryNo = ''
  searchDeliveries()
}

function onDeliveryPageChange(page: number): void {
  deliveryPageNum.value = page
  void loadDeliveries()
}

async function loadReviews(): Promise<void> {
  if (!detailTask.value) return
  try {
    const data = await listGovernanceReviews(detailTask.value.sandboxNo, {
      pageNum: reviewsPageNum.value,
      pageSize: reviewsPageSize,
    })
    reviews.value = data.list
    reviewsTotal.value = data.total
  } catch (error) {
    showError(error)
  }
}

function onReviewsPageChange(page: number): void {
  reviewsPageNum.value = page
  void loadReviews()
}

async function loadActionLogs(): Promise<void> {
  if (!detailTask.value) return
  try {
    const data = await listGovernanceDeliveryActionLogs({
      contractNo: detailTask.value.contractNo,
      pageNum: actionLogsPageNum.value,
      pageSize: actionLogsPageSize,
    })
    actionLogs.value = data.list
    actionLogsTotal.value = data.total
  } catch (error) {
    showError(error)
  }
}

function onActionLogsPageChange(page: number): void {
  actionLogsPageNum.value = page
  void loadActionLogs()
}

async function openSandboxDetail(sandboxNo: string): Promise<void> {
  try {
    detailTask.value = await getGovernanceSandbox(sandboxNo)
    detailResults.value = await listGovernanceSandboxResults(sandboxNo)
    detailVisible.value = true
    reviewsPageNum.value = 1
    actionLogsPageNum.value = 1
    await Promise.all([loadReviews(), loadActionLogs()])
  } catch (error) {
    showError(error)
  }
}

async function openDeliveryDetail(deliveryNo: string): Promise<void> {
  try {
    deliveryDetail.value = await getGovernanceDelivery(deliveryNo)
    deliveryDetailVisible.value = true
  } catch (error) {
    showError(error)
  }
}

function openReview(fileName: string): void {
  reviewForm.fileName = fileName
  reviewForm.conclusion = 'APPROVE'
  reviewForm.reason = ''
  reviewVisible.value = true
}

async function submitReview(): Promise<void> {
  if (!detailTask.value) return
  if (reviewForm.conclusion === 'REJECT' && !reviewForm.reason.trim()) {
    ElMessage.warning('驳回时理由必填')
    return
  }
  reviewSubmitting.value = true
  try {
    await reviewGovernanceResult(detailTask.value.sandboxNo, reviewForm.fileName, {
      conclusion: reviewForm.conclusion,
      ...(reviewForm.conclusion === 'REJECT' ? { reason: reviewForm.reason.trim() } : {}),
    })
    reviewVisible.value = false
    ElMessage.success('审核结论已记录')
    detailResults.value = await listGovernanceSandboxResults(detailTask.value.sandboxNo)
    await loadReviews()
  } catch (error) {
    showError(error)
  } finally {
    reviewSubmitting.value = false
  }
}

onMounted(() => {
  if (!getDemoSubject().trim()) {
    ElMessage.warning(DEMO_SUBJECT_REQUIRED_TIP)
    return
  }
  void loadSandboxes()
  void loadDeliveries()
})
</script>

<style scoped>
.governance-page {
  padding: 16px;
}
.trace-hint {
  margin-bottom: 12px;
}
.filter-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.contract-input {
  width: 180px;
}
.type-select {
  width: 130px;
}
.section-title {
  margin: 16px 0 8px;
  font-size: 14px;
}
.checksum-text {
  word-break: break-all;
  font-family: monospace;
  font-size: 12px;
}
.form-hint {
  margin-bottom: 12px;
}
</style>
