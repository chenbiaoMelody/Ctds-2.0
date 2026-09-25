<script setup lang="ts">
/**
 * WBS-3.1.11 DID 管理（规格 C-1.2 行为 1 规则 4/5、行为 2、行为 4）：
 * 签发记录清单（筛选 + 分页）→ 详情（记录要素 / DID 文档公开要素 / 操作留痕）；
 * 行内操作按记录状态派生（有效→吊销；已吊销→重签；待签发→重试）；吊销后无任何回滚入口（不可逆）。
 * 吊销 = 两段式：理由必填（前置拦截，后端 1005C0002 兜底）→ 二次确认（明示"吊销不可逆"）→
 * 取消不发起任何请求（状态不变、无留痕）。界面只展示 KMS 密钥引用，不展示任何私钥。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '../../api/client'
import {
  fetchDidRecords,
  fetchOperationLogs,
  reissueDid,
  resolveDid,
  retryIssuance,
  revokeDid,
  type DidOperationLogView,
  type DidRecordStatus,
  type DidRecordView,
  type ResolutionView,
} from '../../api/did'
import {
  OPERATION_LABELS,
  RECORDS_EMPTY_TEXT,
  RECORD_STATUS_FILTER_OPTIONS,
  labelOf,
  recordStatusLabel,
  recordStatusType,
} from '../../constants/did'

const router = useRouter()

const loading = ref(false)
const rows = ref<DidRecordView[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const subjectNoFilter = ref('')
const statusFilter = ref<DidRecordStatus | ''>('')

/** 演示入口默认目标：当前清单中第一条已登记（有 DID）的记录（hifi §6.3"从列表带入"）。 */
const demoEntryDid = computed(() => rows.value.find((row) => row.did)?.did ?? null)

const detailVisible = ref(false)
const detailRecord = ref<DidRecordView | null>(null)
const operationLogs = ref<DidOperationLogView[]>([])
const detailLoading = ref(false)
const documentResult = ref<ResolutionView | null>(null)
const documentError = ref('')

const revokeVisible = ref(false)
const revokeTarget = ref<DidRecordView | null>(null)
const revokeReason = ref('')
const submitting = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await fetchDidRecords({
      subjectNo: subjectNoFilter.value,
      status: statusFilter.value,
      pageNum: pageNum.value,
      pageSize: pageSize.value,
    })
    rows.value = page.list
    total.value = page.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '清单加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function query(): void {
  pageNum.value = 1
  void load()
}

function reset(): void {
  subjectNoFilter.value = ''
  statusFilter.value = ''
  pageNum.value = 1
  void load()
}

function changePage(page: number): void {
  pageNum.value = page
  void load()
}

/** 进入演示与验证页：带目标 DID（hifi §6.3"从列表带入 ?did="；无可用 DID 时不带参数）。 */
function goDemo(did?: string | null): void {
  void router.push({ path: '/did/demo', query: did ? { did } : {} })
}

async function copyDid(did: string | null): Promise<void> {
  if (!did) {
    return
  }
  try {
    await navigator.clipboard.writeText(did)
    ElMessage.success('已复制 DID 标识')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

/** 详情：待签发记录无 DID 值 → 空态呈现，不发起留痕请求（E8 界面口径）。 */
async function openDetail(row: DidRecordView): Promise<void> {
  detailRecord.value = row
  detailVisible.value = true
  operationLogs.value = []
  documentResult.value = null
  documentError.value = ''
  if (!row.did) {
    return
  }
  await refreshDetail(row.did)
}

async function refreshDetail(did: string): Promise<void> {
  detailLoading.value = true
  try {
    operationLogs.value = await fetchOperationLogs(did)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作留痕加载失败，请稍后重试')
  } finally {
    detailLoading.value = false
  }
}

async function viewDocument(): Promise<void> {
  const did = detailRecord.value?.did
  if (!did) {
    return
  }
  documentError.value = ''
  try {
    documentResult.value = await resolveDid(did)
  } catch (error) {
    documentResult.value = null
    documentError.value = error instanceof ApiError ? error.message : '解析失败，请稍后重试'
  }
}

function openRevoke(row: DidRecordView): void {
  revokeTarget.value = row
  revokeReason.value = ''
  revokeVisible.value = true
}

/** 吊销第二段：理由必填前置 → 二次确认（取消零请求）→ 调用既有吊销端点 → 刷新。 */
async function submitRevoke(): Promise<void> {
  const reason = revokeReason.value.trim()
  if (!reason) {
    ElMessage.warning('吊销理由必填')
    return
  }
  const target = revokeTarget.value
  if (!target?.did) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `吊销后该 DID 永久失效且不可再次启用（吊销不可逆）。确认吊销 DID：${target.did}？`,
      '吊销二次确认',
      { type: 'warning', confirmButtonText: '确认吊销', cancelButtonText: '取消' },
    )
  } catch {
    // 取消分支：不发起任何请求（DID 状态不变、不产生吊销留痕）
    return
  }
  submitting.value = true
  try {
    await revokeDid(target.did, reason)
    ElMessage.success('已吊销该 DID')
    revokeVisible.value = false
    await load()
    if (detailVisible.value && detailRecord.value?.did === target.did) {
      await refreshDetail(target.did)
    }
  } catch (error) {
    handleOperationError(error)
  } finally {
    submitting.value = false
  }
}

async function reissue(row: DidRecordView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '重签将为该主体生成全新的 DID 与密钥对（签发序号 +1）；旧 DID 永久保留且不可复用。确认重签？',
      '重签确认',
      { type: 'warning', confirmButtonText: '确认重签', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    await reissueDid(row.subjectNo)
    ElMessage.success('已重签，新 DID 已签发')
    await load()
  } catch (error) {
    handleOperationError(error)
  } finally {
    submitting.value = false
  }
}

async function retry(row: DidRecordView): Promise<void> {
  try {
    await ElMessageBox.confirm('将对该主体的待签发记录重新执行签发，确认重试？', '重试确认', {
      type: 'warning',
      confirmButtonText: '确认重试',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  submitting.value = true
  try {
    await retryIssuance(row.subjectNo)
    ElMessage.success('已重试，签发完成')
    await load()
  } catch (error) {
    handleOperationError(error)
  } finally {
    submitting.value = false
  }
}

/** 操作失败：后端业务文案如实透传（不吞、不改写）；状态类拒绝后刷新清单。 */
function handleOperationError(error: unknown): void {
  if (error instanceof ApiError) {
    ElMessage.error(error.message)
    if (['1005C0002', '1005C0003', '1005B0001', '1005B0002'].includes(error.code)) {
      void load()
    }
    return
  }
  ElMessage.error('操作失败，请稍后重试')
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">DID 管理</h2>
        <p class="page-desc">
          主体入驻后由系统自动签发 DID。本页供运营管理员查看签发记录与状态、执行吊销 / 重签 /
          重试，并可进入演示与验证区。
        </p>
      </div>
      <el-button type="primary" plain class="demo-entry-btn" @click="goDemo(demoEntryDid)">
        演示与验证
      </el-button>
    </div>

    <el-card shadow="never" class="block">
      <div class="filters">
        <el-input
          v-model="subjectNoFilter"
          class="filter-subject"
          clearable
          placeholder="如 S20260925000001"
          @keyup.enter="query"
        />
        <el-select v-model="statusFilter" class="filter-status" placeholder="记录状态">
          <el-option
            v-for="option in RECORD_STATUS_FILTER_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-button type="primary" class="query-btn" @click="query">查询</el-button>
        <el-button class="reset-btn" @click="reset">重置</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" :empty-text="RECORDS_EMPTY_TEXT">
        <el-table-column prop="subjectNo" label="主体申请编号" width="180" />
        <el-table-column prop="issuanceSeq" label="签发序号" width="100" />
        <el-table-column label="DID 标识" min-width="240">
          <template #default="scope">
            <span :title="scope.row.did || ''" class="did-cell">{{ scope.row.did || '—' }}</span>
            <el-button v-if="scope.row.did" type="primary" link @click="copyDid(scope.row.did)">
              复制
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="记录状态" width="170">
          <template #default="scope">
            <el-tag :type="recordStatusType(scope.row.status)">
              {{ recordStatusLabel(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="密钥引用" min-width="180">
          <template #default="scope">{{ scope.row.keyRef || '—' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="签发时间" width="180" />
        <el-table-column label="操作" width="220">
          <template #default="scope">
            <el-button type="primary" link @click="openDetail(scope.row)">查看详情</el-button>
            <el-button
              v-if="scope.row.status === 'ACTIVE'"
              type="danger"
              link
              @click="openRevoke(scope.row)"
            >
              吊销
            </el-button>
            <el-button v-if="scope.row.status === 'REVOKED'" type="warning" link @click="reissue(scope.row)">
              重签
            </el-button>
            <el-button
              v-if="scope.row.status === 'PENDING_ISSUE'"
              type="primary"
              link
              @click="retry(scope.row)"
            >
              重试
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="pageNum"
        @current-change="changePage"
      />
    </el-card>

    <el-drawer v-model="detailVisible" title="DID 记录详情" size="60%">
      <template v-if="detailRecord">
        <h3 class="section-title">记录要素</h3>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="主体申请编号">{{ detailRecord.subjectNo }}</el-descriptions-item>
          <el-descriptions-item label="签发序号">{{ detailRecord.issuanceSeq }}</el-descriptions-item>
          <el-descriptions-item label="DID 标识">{{ detailRecord.did || '—' }}</el-descriptions-item>
          <el-descriptions-item label="记录状态">
            <el-tag :type="recordStatusType(detailRecord.status)">
              {{ recordStatusLabel(detailRecord.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="密钥引用">{{ detailRecord.keyRef || '—' }}</el-descriptions-item>
          <el-descriptions-item label="签发时间">{{ detailRecord.createdAt }}</el-descriptions-item>
        </el-descriptions>

        <h3 class="section-title">DID 文档公开要素</h3>
        <el-button type="primary" plain :disabled="!detailRecord.did" @click="viewDocument">
          查看 DID 文档
        </el-button>
        <p class="hint">仅公开要素，界面不展示任何私钥（密钥由 KMS 托管）。</p>
        <el-alert v-if="documentError" class="document-alert" type="warning" :closable="false" :title="documentError" />
        <template v-if="documentResult">
          <p>状态：{{ recordStatusLabel(documentResult.status) }}</p>
          <p>公钥类型：{{ documentResult.document.publicKey?.type ?? '—' }}</p>
          <p>公钥值：{{ documentResult.document.publicKey?.valueHex ?? '—' }}</p>
          <p>控制者（主体编号）：{{ documentResult.document.controller ?? '—' }}</p>
          <p>创建时间：{{ documentResult.document.created ?? '—' }}</p>
        </template>

        <h3 class="section-title">操作留痕</h3>
        <el-table :data="operationLogs" v-loading="detailLoading" empty-text="暂无操作留痕">
          <el-table-column label="操作类型" width="120">
            <template #default="scope">{{ labelOf(OPERATION_LABELS, scope.row.operation) }}</template>
          </el-table-column>
          <el-table-column prop="operator" label="操作人" width="140" />
          <el-table-column prop="occurredAt" label="时间" width="180" />
          <el-table-column label="理由" min-width="160">
            <template #default="scope">{{ scope.row.reason || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态变更" width="180">
            <template #default="scope">
              {{ scope.row.statusFrom || '—' }} → {{ scope.row.statusTo || '—' }}
            </template>
          </el-table-column>
          <el-table-column label="密钥引用" min-width="180">
            <template #default="scope">{{ scope.row.keyRef || '—' }}</template>
          </el-table-column>
        </el-table>

        <div class="drawer-actions">
          <el-button
            type="primary"
            plain
            class="drawer-demo-btn"
            @click="goDemo(detailRecord.did)"
          >
            演示与验证
          </el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="revokeVisible" class="revoke-dialog" title="DID 吊销" width="520">
      <p><span class="label">目标 DID</span>{{ revokeTarget?.did || '—' }}</p>
      <el-input
        v-model="revokeReason"
        class="revoke-reason"
        type="textarea"
        :rows="4"
        maxlength="256"
        show-word-limit
        placeholder="请填写吊销理由，如：私钥疑似泄露"
      />
      <p class="hint">
        吊销不可逆：确认后该 DID 永久失效且不可再次启用，需继续使用身份时须重新签发。
      </p>
      <template #footer>
        <el-button @click="revokeVisible = false">取消</el-button>
        <el-button type="danger" class="revoke-next-btn" :loading="submitting" @click="submitRevoke">
          下一步
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.page-title {
  margin: 0 0 4px;
  font-size: 18px;
}

.page-desc {
  margin: 0 0 16px;
  font-size: 13px;
  color: #6b7280;
}

.block {
  margin-bottom: 12px;
}

.filters {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.filter-subject {
  width: 240px;
}

.filter-status {
  width: 160px;
}

.did-cell {
  display: inline-block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.section-title {
  margin: 16px 0 8px;
  font-size: 15px;
}

.hint {
  margin: 8px 0;
  font-size: 13px;
  color: #6b7280;
}

.label {
  display: inline-block;
  width: 96px;
  color: #6b7280;
}

.drawer-actions {
  margin-top: 16px;
}
</style>
