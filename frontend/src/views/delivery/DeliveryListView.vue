<template>
  <div class="delivery-page">
    <div class="operation-bar">
      <el-button type="primary" @click="openCreate">发起交付</el-button>
    </div>

    <div class="filter-bar">
      <el-input v-model="filters.contractNo" clearable placeholder="合约编号" class="contract-input" />
      <el-select v-model="filters.deliveryType" clearable placeholder="交付形态" class="type-select">
        <el-option
          v-for="(label, code) in DELIVERY_TYPE_LABELS"
          :key="code"
          :label="label"
          :value="code"
        />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="resetFilter">重置</el-button>
    </div>

    <el-table v-loading="loading" :data="list" empty-text="暂无交付记录">
      <el-table-column prop="deliveryNo" label="交付编号" width="120" />
      <el-table-column prop="contractNo" label="合约编号" width="110" />
      <el-table-column prop="productName" label="产品名称" min-width="150" />
      <el-table-column label="形态" width="100">
        <template #default="scope">
          <el-tag :type="DELIVERY_TYPE_TAG_TYPES[scope.row.deliveryType] ?? 'info'">
            {{ DELIVERY_TYPE_LABELS[scope.row.deliveryType] ?? scope.row.deliveryType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="requesterNo" label="发起方" width="145" />
      <el-table-column prop="providerNo" label="提供方" width="145" />
      <el-table-column label="文件名" min-width="130">
        <template #default="scope">{{ scope.row.fileName ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="大小" width="95">
        <template #default="scope">{{ humanSize(scope.row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="结果" width="80">
        <template #default="scope">{{ ACTION_LOG_RESULT_LABELS[scope.row.result] ?? scope.row.result }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" width="165" />
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="scope">
          <el-button link type="primary" @click="openDetail(scope.row.deliveryNo)">详情</el-button>
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

    <el-drawer v-model="detailVisible" title="交付详情" size="480px">
      <el-descriptions v-if="detail" :column="1" border>
        <el-descriptions-item label="交付编号">{{ detail.deliveryNo }}</el-descriptions-item>
        <el-descriptions-item label="合约编号">{{ detail.contractNo }}</el-descriptions-item>
        <el-descriptions-item label="产品名称">{{ detail.productName }}</el-descriptions-item>
        <el-descriptions-item label="形态">
          {{ DELIVERY_TYPE_LABELS[detail.deliveryType] ?? detail.deliveryType }}
        </el-descriptions-item>
        <el-descriptions-item label="发起方">{{ detail.requesterNo }}</el-descriptions-item>
        <el-descriptions-item label="提供方">{{ detail.providerNo }}</el-descriptions-item>
        <el-descriptions-item label="文件标识">{{ detail.fileId ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="文件名">{{ detail.fileName ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="大小">{{ humanSize(detail.fileSize) }}</el-descriptions-item>
        <el-descriptions-item label="SM3 校验值">
          <span class="checksum-text">{{ detail.checksum ?? '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="结果">
          {{ ACTION_LOG_RESULT_LABELS[detail.result] ?? detail.result }}
        </el-descriptions-item>
        <el-descriptions-item label="时间">{{ detail.createdAt }}</el-descriptions-item>
      </el-descriptions>
      <el-button
        v-if="detail && canDownload"
        type="primary"
        :loading="downloading"
        class="download-btn"
        @click="download"
      >下载加密包</el-button>
    </el-drawer>

    <el-dialog v-model="createVisible" title="发起交付" width="640px" destroy-on-close>
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
        <el-form-item label="交付形态" required>
          <el-radio-group v-model="createForm.deliveryType">
            <el-radio v-for="opt in DELIVERY_TYPE_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="createForm.deliveryType === 'FILE'" label="文件标识" required>
          <el-input v-model="createForm.fileId" placeholder="文件标识（如 F13-DEMO-001）" />
        </el-form-item>
        <el-form-item label="用途">
          <el-input v-model="createForm.purpose" placeholder="自由填写（是否合规由服务端判定）" />
        </el-form-item>
        <el-form-item label="地域">
          <el-input v-model="createForm.territory" placeholder="自由填写（是否合规由服务端判定）" />
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  DELIVERY_TYPE_LABELS,
  DELIVERY_TYPE_TAG_TYPES,
  DELIVERY_TYPE_OPTIONS,
  ACTION_LOG_RESULT_LABELS,
  PACKAGE_DOWNLOAD_SM3_HINT,
  FILE_ID_REQUIRED_TIP,
} from '../../constants/delivery'
import {
  listDeliveries,
  getDelivery,
  requestDelivery,
  downloadDeliveryPackage,
  type DeliveryRecord,
} from '../../api/delivery'
import { listMyContracts } from '../../api/contract'
import { getDemoSubject, newIdempotencyKey } from '../../api/client'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../../constants/space'

/**
 * WBS-3.5.6「交付记录」页（hifi §6.1 P1）：参与者读面 = 过滤（合约 / 形态）+ 分页列表 +
 * 详情抽屉（SM3 校验值 + 需求方专属加密包下载——体验层显隐，服务端仍强制）+
 * 「发起交付」弹窗（合约直填 + 检索回填 + 形态联动 fileId + 要素自由填写——判定在服务端；
 * 弹窗实例级幂等键 Q3-A）。业务标签中文一律经 constants（T14 源集守卫）。
 */
const router = useRouter()
const loading = ref(false)
const list = ref<DeliveryRecord[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 10

const filters = reactive({ contractNo: '', deliveryType: '' })

const detailVisible = ref(false)
const detail = ref<DeliveryRecord | null>(null)
const downloading = ref(false)

const createVisible = ref(false)
const submitting = ref(false)
const createForm = reactive({
  contractNo: '',
  deliveryType: 'API' as 'API' | 'FILE',
  fileId: '',
  purpose: '',
  territory: '',
})
const idempotencyKey = ref('')
const contractOptions = ref<Array<{ contractNo: string; productName: string }>>([])

/** 下载可见性（体验层显隐；服务端仍强制）：带包形态 + 当前主体 = 需求方。 */
const canDownload = computed(() => {
  if (!detail.value) return false
  if (detail.value.deliveryType !== 'FILE' && detail.value.deliveryType !== 'SANDBOX_EXPORT') {
    return false
  }
  return detail.value.requesterNo === getDemoSubject()
})

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
    const data = await listDeliveries({
      pageNum: pageNum.value,
      pageSize,
      ...(filters.contractNo ? { contractNo: filters.contractNo } : {}),
      ...(filters.deliveryType ? { deliveryType: filters.deliveryType } : {}),
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
  filters.deliveryType = ''
  search()
}

function onPageChange(page: number): void {
  pageNum.value = page
  void load()
}

function humanSize(size: number | null): string {
  if (size == null) return '—'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(2)} MB`
}

async function openDetail(deliveryNo: string): Promise<void> {
  try {
    detail.value = await getDelivery(deliveryNo)
    detailVisible.value = true
  } catch (error) {
    showError(error)
  }
}

async function download(): Promise<void> {
  if (!detail.value) return
  downloading.value = true
  try {
    const pkg = await downloadDeliveryPackage(detail.value.deliveryNo)
    const url = URL.createObjectURL(pkg.blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = pkg.fileName
    anchor.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`${PACKAGE_DOWNLOAD_SM3_HINT}${pkg.sm3}`)
  } catch (error) {
    showError(error)
  } finally {
    downloading.value = false
  }
}

function openCreate(): void {
  createForm.contractNo = ''
  createForm.deliveryType = 'API'
  createForm.fileId = ''
  createForm.purpose = ''
  createForm.territory = ''
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
  if (createForm.deliveryType === 'FILE' && !createForm.fileId.trim()) {
    ElMessage.warning(FILE_ID_REQUIRED_TIP)
    return
  }
  submitting.value = true
  try {
    await requestDelivery(idempotencyKey.value, {
      contractNo: createForm.contractNo.trim(),
      deliveryType: createForm.deliveryType,
      ...(createForm.deliveryType === 'FILE' ? { fileId: createForm.fileId.trim() } : {}),
      ...(createForm.purpose.trim() ? { purpose: createForm.purpose.trim() } : {}),
      ...(createForm.territory.trim() ? { territory: createForm.territory.trim() } : {}),
    })
    createVisible.value = false
    ElMessage.success('交付已发起')
    pageNum.value = 1
    await load()
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
.delivery-page {
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
  width: 150px;
}
.checksum-text {
  word-break: break-all;
  font-family: monospace;
}
.download-btn {
  margin-top: 12px;
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
