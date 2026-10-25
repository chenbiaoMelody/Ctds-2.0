<template>
  <div class="sandbox-detail-page">
    <div class="page-header">
      <el-button link @click="goBack">← 返回</el-button>
      <h2 class="page-title">沙箱任务 {{ sandboxNo }}</h2>
    </div>

    <!-- A 基本信息与配额生命周期区 -->
    <el-card class="section-card" shadow="never">
      <template #header>
        <div class="section-header">
          <span>基本信息与配额</span>
          <el-button
            v-if="task?.status === 'RUNNING'"
            type="danger"
            link
            @click="confirmClose"
          >关闭任务</el-button>
        </div>
      </template>
      <el-descriptions v-if="task" :column="2" border>
        <el-descriptions-item label="任务编号">{{ task.sandboxNo }}</el-descriptions-item>
        <el-descriptions-item label="合约编号">{{ task.contractNo }}</el-descriptions-item>
        <el-descriptions-item label="产品名称">{{ task.productName }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="SANDBOX_STATUS_TAG_TYPES[task.status] ?? 'info'">
            {{ SANDBOX_STATUS_LABELS[task.status] ?? task.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="需求方">{{ task.requesterNo }}</el-descriptions-item>
        <el-descriptions-item label="提供方">{{ task.providerNo }}</el-descriptions-item>
        <el-descriptions-item :label="SANDBOX_QUOTA_COLUMN_LABEL">
          {{ task.cpus }}C·{{ task.memoryMb }}MB·{{ task.ttlSeconds }}S
        </el-descriptions-item>
        <el-descriptions-item label="数据文件数">{{ task.dataFileCount }}</el-descriptions-item>
        <el-descriptions-item label="到期时间">{{ task.expiresAt ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="关闭原因">{{ task.closeReason ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="开通时间" :span="2">{{ task.createdAt }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- B 沙箱工作台区 -->
    <el-card class="section-card" shadow="never">
      <template #header>
        <div class="section-header">
          <span>沙箱工作台</span>
          <el-button
            v-if="task?.status === 'RUNNING'"
            type="primary"
            @click="openRunJob"
          >运行作业</el-button>
        </div>
      </template>
      <el-alert :title="SANDBOX_EXIT_BOUNDARY_HINT" type="warning" :closable="false" />
      <el-table v-loading="jobsLoading" :data="jobs" empty-text="暂无作业记录" class="section-table">
        <el-table-column prop="jobNo" label="作业编号" width="120" />
        <el-table-column label="作业" width="110">
          <template #default="scope">{{ jobLabel(scope.row.jobId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="scope">
            <el-tag :type="JOB_STATUS_TAG_TYPES[scope.row.status] ?? 'info'">
              {{ JOB_STATUS_LABELS[scope.row.status] ?? scope.row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="退出码" width="80">
          <template #default="scope">{{ scope.row.exitCode ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="时长(ms)" width="90">
          <template #default="scope">{{ scope.row.durationMs ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="purpose" label="用途" min-width="110">
          <template #default="scope">{{ scope.row.purpose ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="territory" label="地域" min-width="100">
          <template #default="scope">{{ scope.row.territory ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="165" />
      </el-table>
      <el-pagination
        v-if="jobsTotal > jobsPageSize"
        layout="total, prev, pager, next"
        :total="jobsTotal"
        :page-size="jobsPageSize"
        :current-page="jobsPageNum"
        @current-change="onJobsPageChange"
      />
    </el-card>

    <!-- D 结果物区 -->
    <el-card class="section-card" shadow="never">
      <template #header><span>结果物</span></template>
      <el-table v-loading="resultsLoading" :data="results" empty-text="暂无结果物">
        <el-table-column prop="fileName" label="文件名" min-width="160" />
        <el-table-column label="大小" width="95">
          <template #default="scope">{{ humanSize(scope.row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="SM3" width="150">
          <template #default="scope">
            <span class="checksum-text">{{ scope.row.checksum ?? '—（超大文件不提供摘要）' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="生成时间" width="165" />
        <el-table-column label="审核状态" width="95">
          <template #default="scope">
            <el-tag :type="RESULT_STATUS_TAG_TYPES[scope.row.status ?? ''] ?? 'info'">
              {{ RESULT_STATUS_LABELS[scope.row.status ?? ''] ?? scope.row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结论" width="80">
          <template #default="scope">
            {{ scope.row.conclusion ? REVIEW_CONCLUSION_LABELS[scope.row.conclusion] : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="驳回理由" min-width="160">
          <template #default="scope">{{ scope.row.rejectReason ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="轮次" width="70">
          <template #default="scope">{{ scope.row.reviewRound ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="scope">
            <el-tooltip
              v-if="scope.row.status === 'PENDING'"
              :content="SUBMIT_DISABLED_PENDING_HINT"
              placement="top"
            >
              <el-button link type="primary" disabled>提交审核</el-button>
            </el-tooltip>
            <el-button
              v-else-if="scope.row.status === null || scope.row.status === 'REJECTED'"
              link
              type="primary"
              :disabled="submitKey !== ''"
              @click="submitReview(scope.row.fileName)"
            >{{ scope.row.status === 'REJECTED' ? '重新提交' : '提交审核' }}</el-button>
            <el-tooltip
              v-if="scope.row.status !== 'APPROVED'"
              :content="exportDisabledHint(scope.row.status)"
              placement="top"
            >
              <el-button link type="success" disabled>导出</el-button>
            </el-tooltip>
            <el-button v-else link type="success" @click="openExport(scope.row.fileName)">导出</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- E 导出记录区 -->
    <el-card class="section-card" shadow="never">
      <template #header><span>导出记录</span></template>
      <el-table v-loading="exportsLoading" :data="exports" empty-text="暂无导出记录">
        <el-table-column prop="deliveryNo" label="导出编号" width="120" />
        <el-table-column prop="contractNo" label="合约编号" width="110" />
        <el-table-column prop="resultName" label="结果物" min-width="150" />
        <el-table-column prop="fileName" label="文件名" min-width="140" />
        <el-table-column label="大小" width="95">
          <template #default="scope">{{ humanSize(scope.row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="SM3" width="150">
          <template #default="scope">
            <span class="checksum-text">{{ scope.row.checksum ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="80">
          <template #default="scope">
            {{ ACTION_LOG_RESULT_LABELS[scope.row.result] ?? scope.row.result }}
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="165" />
      </el-table>
      <el-pagination
        v-if="exportsTotal > exportsPageSize"
        layout="total, prev, pager, next"
        :total="exportsTotal"
        :page-size="exportsPageSize"
        :current-page="exportsPageNum"
        @current-change="onExportsPageChange"
      />
    </el-card>

    <!-- 运行作业弹窗 -->
    <el-dialog v-model="runJobVisible" title="运行作业" width="520px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="作业" required>
          <el-select v-model="jobForm.jobId" placeholder="选择受控作业">
            <el-option v-for="opt in JOB_ID_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="用途">
          <el-input v-model="jobForm.purpose" placeholder="自由填写（是否合规由服务端判定）" />
        </el-form-item>
        <el-form-item label="地域">
          <el-input v-model="jobForm.territory" placeholder="自由填写（是否合规由服务端判定）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="runJobVisible = false">取消</el-button>
        <el-button type="primary" :loading="runJobSubmitting" @click="submitRunJob">提交</el-button>
      </template>
    </el-dialog>

    <!-- 导出弹窗 -->
    <el-dialog v-model="exportVisible" title="导出结果物" width="520px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="结果物">
          <el-input v-model="exportForm.fileName" disabled />
        </el-form-item>
        <el-form-item label="用途">
          <el-input v-model="exportForm.purpose" placeholder="可选（是否合规由服务端判定）" />
        </el-form-item>
        <el-form-item label="地域">
          <el-input v-model="exportForm.territory" placeholder="可选（是否合规由服务端判定）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exportVisible = false">取消</el-button>
        <el-button type="primary" :loading="exportSubmitting" @click="submitExport">确认导出</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  SANDBOX_STATUS_LABELS,
  SANDBOX_STATUS_TAG_TYPES,
  SANDBOX_QUOTA_COLUMN_LABEL,
  SANDBOX_EXIT_BOUNDARY_HINT,
  RESULT_STATUS_LABELS,
  RESULT_STATUS_TAG_TYPES,
  REVIEW_CONCLUSION_LABELS,
  JOB_ID_OPTIONS,
  JOB_STATUS_LABELS,
  JOB_STATUS_TAG_TYPES,
  ACTION_LOG_RESULT_LABELS,
  SUBMIT_DISABLED_PENDING_HINT,
  EXPORT_DISABLED_PENDING_HINT,
  EXPORT_DISABLED_REJECTED_HINT,
  EXPORT_DISABLED_UNREGISTERED_HINT,
  CLOSE_SANDBOX_CONFIRM_TIP,
  CLOSE_SANDBOX_SUCCESS_TIP,
} from '../../constants/delivery'
import {
  getSandbox,
  listSandboxJobs,
  listSandboxResults,
  listSandboxExports,
  closeSandbox,
  runSandboxJob,
  submitResultReview,
  exportSandboxResult,
  type SandboxRecord,
  type SandboxJobRecord,
  type SandboxResultItem,
  type SandboxExportRecord,
} from '../../api/delivery'
import { getDemoSubject, newIdempotencyKey } from '../../api/client'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../../constants/space'

/**
 * WBS-3.5.6 沙箱任务详情深链页（hifi §6.3 P3）：基本信息与配额生命周期区（关闭仅 RUNNING）+
 * 沙箱工作台（唯一出口诚实边界文案常驻 + 运行作业弹窗 + 作业历史）+ 结果物区
 * （状态机显隐 = ADR-025：提交审核仅 status 空值与 REJECTED 可提交、PENDING 禁用、
 * APPROVED 不显示；导出仅 APPROVED）+ 导出记录区。四请求并行装载（S2+S6+S7+S10）。
 */
const route = useRoute()
const router = useRouter()
const sandboxNo = String(route.params.sandboxNo ?? '')

const task = ref<SandboxRecord | null>(null)

const jobsLoading = ref(false)
const jobs = ref<SandboxJobRecord[]>([])
const jobsTotal = ref(0)
const jobsPageNum = ref(1)
const jobsPageSize = 10

const resultsLoading = ref(false)
const results = ref<SandboxResultItem[]>([])

const exportsLoading = ref(false)
const exports = ref<SandboxExportRecord[]>([])
const exportsTotal = ref(0)
const exportsPageNum = ref(1)
const exportsPageSize = 10

const runJobVisible = ref(false)
const runJobSubmitting = ref(false)
const jobForm = reactive({ jobId: '', purpose: '', territory: '' })
const runJobKey = ref('')

const exportVisible = ref(false)
const exportSubmitting = ref(false)
const exportForm = reactive({ fileName: '', purpose: '', territory: '' })
const exportKey = ref('')

const submitKey = ref('')

function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error(error instanceof Error ? error.message : String(error))
}

async function loadTask(): Promise<void> {
  try {
    task.value = await getSandbox(sandboxNo)
  } catch (error) {
    showError(error)
  }
}

async function loadJobs(): Promise<void> {
  jobsLoading.value = true
  try {
    const data = await listSandboxJobs(sandboxNo, {
      pageNum: jobsPageNum.value,
      pageSize: jobsPageSize,
    })
    jobs.value = data.list
    jobsTotal.value = data.total
  } catch (error) {
    showError(error)
  } finally {
    jobsLoading.value = false
  }
}

async function loadResults(): Promise<void> {
  resultsLoading.value = true
  try {
    results.value = await listSandboxResults(sandboxNo)
  } catch (error) {
    showError(error)
  } finally {
    resultsLoading.value = false
  }
}

async function loadExports(): Promise<void> {
  exportsLoading.value = true
  try {
    const data = await listSandboxExports(sandboxNo, {
      pageNum: exportsPageNum.value,
      pageSize: exportsPageSize,
    })
    exports.value = data.list
    exportsTotal.value = data.total
  } catch (error) {
    showError(error)
  } finally {
    exportsLoading.value = false
  }
}

function onJobsPageChange(page: number): void {
  jobsPageNum.value = page
  void loadJobs()
}

function onExportsPageChange(page: number): void {
  exportsPageNum.value = page
  void loadExports()
}

function goBack(): void {
  void router.push('/sandboxes')
}

function humanSize(size: number | null): string {
  if (size == null) return '—'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(2)} MB`
}

function jobLabel(jobId: string): string {
  return JOB_ID_OPTIONS.find((o) => o.value === jobId)?.label ?? jobId
}

function exportDisabledHint(status: string | null): string {
  if (status === 'PENDING') return EXPORT_DISABLED_PENDING_HINT
  if (status === 'REJECTED') return EXPORT_DISABLED_REJECTED_HINT
  return EXPORT_DISABLED_UNREGISTERED_HINT
}

async function confirmClose(): Promise<void> {
  try {
    await ElMessageBox.confirm(CLOSE_SANDBOX_CONFIRM_TIP, '关闭任务', { type: 'warning' })
  } catch {
    return
  }
  try {
    task.value = await closeSandbox(sandboxNo)
    ElMessage.success(CLOSE_SANDBOX_SUCCESS_TIP)
    await Promise.all([loadJobs(), loadResults()])
  } catch (error) {
    showError(error)
  }
}

function openRunJob(): void {
  jobForm.jobId = ''
  jobForm.purpose = ''
  jobForm.territory = ''
  runJobKey.value = newIdempotencyKey()
  runJobVisible.value = true
}

async function submitRunJob(): Promise<void> {
  if (!jobForm.jobId) {
    ElMessage.warning('请选择作业')
    return
  }
  runJobSubmitting.value = true
  try {
    const job = await runSandboxJob(runJobKey.value, sandboxNo, {
      jobId: jobForm.jobId,
      ...(jobForm.purpose.trim() ? { purpose: jobForm.purpose.trim() } : {}),
      ...(jobForm.territory.trim() ? { territory: jobForm.territory.trim() } : {}),
    })
    runJobVisible.value = false
    if (job.status === 'SUCCEEDED') {
      ElMessage.success('作业执行成功')
    } else {
      ElMessage.warning(`作业未成功（${job.status}），详见作业历史`)
    }
    await loadJobs()
  } catch (error) {
    showError(error)
  } finally {
    runJobSubmitting.value = false
  }
}

async function submitReview(fileName: string): Promise<void> {
  // 请求进行中幂等键保持不变（双击同键重放语义）；失败清空可换键重试（Q3-A 同构口径）
  if (submitKey.value) return
  submitKey.value = newIdempotencyKey()
  try {
    await submitResultReview(submitKey.value, sandboxNo, fileName)
    ElMessage.success('已提交审核（待治理审核）')
    await loadResults()
  } catch (error) {
    showError(error)
  } finally {
    submitKey.value = ''
  }
}

function openExport(fileName: string): void {
  exportForm.fileName = fileName
  exportForm.purpose = ''
  exportForm.territory = ''
  exportKey.value = newIdempotencyKey()
  exportVisible.value = true
}

async function submitExport(): Promise<void> {
  exportSubmitting.value = true
  try {
    const record = await exportSandboxResult(exportKey.value, sandboxNo, exportForm.fileName, {
      ...(exportForm.purpose.trim() ? { purpose: exportForm.purpose.trim() } : {}),
      ...(exportForm.territory.trim() ? { territory: exportForm.territory.trim() } : {}),
    })
    exportVisible.value = false
    ElMessage.success(`导出成功（${record.deliveryNo}）`)
    await Promise.all([loadResults(), loadExports()])
  } catch (error) {
    showError(error)
  } finally {
    exportSubmitting.value = false
  }
}

onMounted(() => {
  if (!getDemoSubject().trim()) {
    ElMessage.warning(DEMO_SUBJECT_REQUIRED_TIP)
    return
  }
  void loadTask()
  void loadJobs()
  void loadResults()
  void loadExports()
})
</script>

<style scoped>
.sandbox-detail-page {
  padding: 16px;
}
.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.page-title {
  margin: 0;
  font-size: 18px;
}
.section-card {
  margin-bottom: 16px;
}
.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.section-table {
  margin-top: 12px;
}
.checksum-text {
  word-break: break-all;
  font-family: monospace;
  font-size: 12px;
}
</style>
