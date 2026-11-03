<template>
  <div class="portal-bill-list-page">
    <h2 class="page-title">我的账单</h2>
    <p class="page-desc">{{ PORTAL_BILL_PAGE_DESC }}</p>

    <div class="filter-bar">
      <el-input v-model="filters.contractNo" class="filter-contract" placeholder="合约号" clearable style="width: 160px" />
      <el-date-picker
        v-model="filters.period"
        class="filter-period"
        type="month"
        value-format="YYYY-MM"
        placeholder="账期"
        style="width: 140px"
      />
      <el-input v-model="filters.productId" class="filter-product" placeholder="产品 ID" clearable style="width: 120px" />
      <el-button type="primary" class="bill-search-btn" :loading="loading" @click="search">{{ SEARCH_LABEL }}</el-button>
      <el-button class="bill-reset-btn" @click="reset">{{ RESET_LABEL }}</el-button>
    </div>

    <el-alert v-if="errorTip" class="list-error" :title="errorTip" type="error" :closable="false" show-icon />

    <el-table v-loading="loading" :data="bills" class="bill-table" size="small">
      <el-table-column label="账单号" width="120">
        <template #default="scope">
          <RouterLink class="bill-link" :to="`/portal/bills/${encodeURIComponent(scope.row.billNo)}`">
            {{ scope.row.billNo }}
          </RouterLink>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="scope">{{ labelOf(BILL_TYPE_LABELS, scope.row.billType) }}</template>
      </el-table-column>
      <el-table-column prop="productName" label="产品" min-width="160" show-overflow-tooltip />
      <el-table-column prop="period" label="账期" width="100" />
      <el-table-column label="我的角色" width="100">
        <template #default="scope">{{ labelOf(MY_ROLE_LABELS, scope.row.myRole) }}</template>
      </el-table-column>
      <el-table-column label="金额" width="110" align="right">
        <template #default="scope">{{ scope.row.amount }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="scope">
          <el-tag :type="BILL_STATUS_TAG[scope.row.status] ?? 'info'">
            {{ labelOf(BILL_STATUS_LABELS, scope.row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="生成时点" width="180" />
      <template #empty>
        <span class="empty-tip">{{ PORTAL_BILL_EMPTY_TEXT }}</span>
      </template>
    </el-table>

    <el-pagination
      v-if="total > pageSize"
      class="list-pagination"
      layout="total, sizes, prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :page-sizes="PAGE_SIZES"
      :current-page="pageNum"
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listMyBills, type BillSummary } from '../../api/billing'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { labelOf } from '../../constants/catalog'
import {
  BILL_STATUS_LABELS,
  BILL_STATUS_TAG,
  BILL_TYPE_LABELS,
  PORTAL_BILL_EMPTY_TEXT,
  PORTAL_BILL_PAGE_DESC,
  RESET_LABEL,
  SEARCH_LABEL,
} from '../../constants/billing'
import { MY_ROLE_LABELS } from '../../constants/order'

/**
 * 门户「我的账单」列表（WBS-3.7.7 hifi §3 P10；menuOrder 25，登录可见）：
 * 本方账单（需方 / 供方）三过滤 + 分页 + 空态 / 失败态；`myRole` 由服务端判定，
 * 前端仅按标签渲染；零写动作、零治理端点、角色名零出现（T15 静态纪律）。
 */
const router = useRouter()

const bills = ref<BillSummary[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
/** 分页档位（hifi §3 P1 口径，与运营侧一致：10 / 20 / 50）。 */
const PAGE_SIZES = [10, 20, 50]
const loading = ref(false)
const errorTip = ref('')
const filters = reactive({
  contractNo: '',
  period: null as string | null,
  productId: '',
})

function numberOrUndefined(value: string): number | undefined {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : undefined
}

async function loadBills(): Promise<void> {
  loading.value = true
  errorTip.value = ''
  try {
    const page = await listMyBills({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      contractNo: filters.contractNo.trim() || undefined,
      period: filters.period ?? undefined,
      productId: numberOrUndefined(filters.productId),
    })
    bills.value = page.list
    total.value = page.total
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    bills.value = []
    total.value = 0
    errorTip.value = error instanceof Error ? error.message : String(error)
  } finally {
    loading.value = false
  }
}

function search(): void {
  pageNum.value = 1
  void loadBills()
}

function reset(): void {
  filters.contractNo = ''
  filters.period = null
  filters.productId = ''
  search()
}

function onPageChange(page: number): void {
  pageNum.value = page
  void loadBills()
}

/** 分页档位变更：回第 1 页重新拉取。 */
function onSizeChange(size: number): void {
  pageSize.value = size
  pageNum.value = 1
  void loadBills()
}

onMounted(() => {
  void loadBills()
})
</script>

<style scoped>
.portal-bill-list-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.page-title {
  margin: 0;
  font-size: 20px;
}

.page-desc {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.empty-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.list-pagination {
  align-self: flex-end;
}

.bill-link {
  color: var(--el-color-primary);
  text-decoration: none;
}
</style>
