<template>
  <div class="sandbox-page">
    <div class="operation-bar">
      <el-button type="primary" @click="openCreate">申请沙箱</el-button>
    </div>

    <div class="filter-bar">
      <el-input v-model="filters.contractNo" clearable placeholder="合约编号" class="contract-input" />
      <el-select v-model="filters.status" clearable placeholder="状态" class="type-select">
        <el-option
          v-for="opt in SANDBOX_STATUS_OPTIONS"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="resetFilter">重置</el-button>
    </div>

    <el-table v-loading="loading" :data="list" empty-text="暂无沙箱任务">
      <el-table-column prop="sandboxNo" label="任务编号" width="120" />
      <el-table-column prop="contractNo" label="合约编号" width="110" />
      <el-table-column prop="productName" label="产品名称" min-width="140" />
      <el-table-column prop="requesterNo" label="需求方" width="145" />
      <el-table-column prop="providerNo" label="提供方" width="145" />
      <el-table-column label="状态" width="90">
        <template #default="scope">
          <el-tag :type="SANDBOX_STATUS_TAG_TYPES[scope.row.status] ?? 'info'">
            {{ SANDBOX_STATUS_LABELS[scope.row.status] ?? scope.row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="SANDBOX_QUOTA_COLUMN_LABEL" width="150">
        <template #default="scope">{{ quotaSummary(scope.row) }}</template>
      </el-table-column>
      <el-table-column prop="expiresAt" label="到期时间" width="165">
        <template #default="scope">{{ scope.row.expiresAt ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="关闭原因" width="150">
        <template #default="scope">{{ scope.row.closeReason ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="scope">
          <el-button link type="primary" @click="goDetail(scope.row.sandboxNo)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > pageSize"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="pageNum"
      @current-change="onPageChange"
    />

    <el-dialog v-model="createVisible" title="申请沙箱" width="560px" destroy-on-close>
      <el-alert :title="SANDBOX_FORM_HINT" type="info" :closable="false" class="form-hint" />
      <el-form label-width="110px">
        <el-form-item label="合约编号" required>
          <div class="contract-row">
            <el-input v-model="createForm.contractNo" placeholder="已生效合约编号（可直填）" />
            <el-button link type="primary" @click="searchContracts">检索</el-button>
          </div>
          <el-select
            v-if="contractOptions.length"
            v-model="createForm.contractNo"
            placeholder="检索结果（可选回填）"
            class="contract-select"
          >
            <el-option
              v-for="c in contractOptions"
              :key="c.contractNo"
              :label="`${c.contractNo}（${c.productName}）`"
              :value="c.contractNo"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  SANDBOX_STATUS_LABELS,
  SANDBOX_STATUS_TAG_TYPES,
  SANDBOX_STATUS_OPTIONS,
  SANDBOX_FORM_HINT,
  SANDBOX_QUOTA_COLUMN_LABEL,
} from '../../constants/delivery'
import { listSandboxes, requestSandbox, type SandboxRecord } from '../../api/delivery'
import { listMyContracts } from '../../api/contract'
import { getDemoSubject, newIdempotencyKey } from '../../api/client'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../../constants/space'

/**
 * WBS-3.5.6「沙箱任务」页（hifi §6.2 P2）：过滤（合约 / 状态）+ 分页列表（配额摘要 +
 * 终态关闭原因）+ 行操作「详情」深链 +「申请沙箱」弹窗（G1 口径提示常驻——判定在服务端；
 * 弹窗实例级幂等键；成功跳深链）。
 */
const router = useRouter()
const loading = ref(false)
const list = ref<SandboxRecord[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 10

const filters = reactive({ contractNo: '', status: '' })

const createVisible = ref(false)
const submitting = ref(false)
const createForm = reactive({ contractNo: '' })
const idempotencyKey = ref('')
const contractOptions = ref<Array<{ contractNo: string; productName: string }>>([])

function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error(error instanceof Error ? error.message : String(error))
}

async function load(): Promise<void> {
  if (!getDemoSubject().trim()) {
    ElMessage.warning(DEMO_SUBJECT_REQUIRED_TIP)
    return
  }
  loading.value = true
  try {
    const data = await listSandboxes({
      pageNum: pageNum.value,
      pageSize,
      ...(filters.contractNo ? { contractNo: filters.contractNo } : {}),
      ...(filters.status ? { status: filters.status } : {}),
    })
    list.value = data.list
    total.value = data.total
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

function search(): void {
  pageNum.value = 1
  void load()
}

function resetFilter(): void {
  filters.contractNo = ''
  filters.status = ''
  search()
}

function onPageChange(page: number): void {
  pageNum.value = page
  void load()
}

function quotaSummary(task: SandboxRecord): string {
  return `${task.cpus}C·${task.memoryMb}MB·${task.ttlSeconds}S`
}

function goDetail(sandboxNo: string): void {
  void router.push(`/sandboxes/${encodeURIComponent(sandboxNo)}`)
}

function openCreate(): void {
  createForm.contractNo = ''
  contractOptions.value = []
  idempotencyKey.value = newIdempotencyKey()
  createVisible.value = true
}

async function searchContracts(): Promise<void> {
  if (!getDemoSubject().trim()) {
    ElMessage.warning(DEMO_SUBJECT_REQUIRED_TIP)
    return
  }
  try {
    const data = await listMyContracts({ pageNum: 1, pageSize: 50, status: 'EFFECTIVE' })
    contractOptions.value = data.list.map((c) => ({
      contractNo: c.contractNo,
      productName: c.productName,
    }))
    if (!contractOptions.value.length) {
      ElMessage.info('检索无已生效合约')
    }
  } catch (error) {
    showError(error)
  }
}

async function submitCreate(): Promise<void> {
  if (!createForm.contractNo.trim()) {
    ElMessage.warning('请填写合约编号')
    return
  }
  submitting.value = true
  try {
    const task = await requestSandbox(idempotencyKey.value, {
      contractNo: createForm.contractNo.trim(),
    })
    createVisible.value = false
    ElMessage.success('沙箱任务已开通')
    await router.push(`/sandboxes/${encodeURIComponent(task.sandboxNo)}`)
  } catch (error) {
    showError(error)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.sandbox-page {
  padding: 16px;
}
.operation-bar {
  margin-bottom: 12px;
}
.filter-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.contract-input {
  width: 200px;
}
.type-select {
  width: 130px;
}
.form-hint {
  margin-bottom: 12px;
}
.contract-row {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
}
.contract-select {
  width: 100%;
  margin-top: 4px;
}
</style>
