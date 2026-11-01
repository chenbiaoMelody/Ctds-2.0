<template>
  <div class="order-list-page">
    <RouterLink class="back-link" to="/portal">← 返回数据产品门户</RouterLink>
    <h2 class="page-title">我的订单</h2>

    <div class="filter-bar">
      <el-select v-model="statusFilter" class="status-filter" placeholder="全部状态" clearable style="width: 180px">
        <el-option
          v-for="(label, value) in ORDER_STATUS_LABELS"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <el-button class="search-btn" type="primary" :loading="loading" @click="loadFirstPage">查询</el-button>
      <el-button @click="onReset">重置</el-button>
    </div>

    <el-alert v-if="errorTip" class="list-error" :title="errorTip" type="error" :closable="false" show-icon />

    <el-table v-loading="loading" :data="orders" class="order-table">
      <el-table-column prop="orderNo" label="订单号" width="120" />
      <el-table-column prop="productName" label="产品名称" min-width="180" show-overflow-tooltip />
      <el-table-column label="定价" width="140">
        <template #default="scope">{{ formatPortalPricing(labelOf(PRICING_MODEL_LABELS, scope.row.pricingModel), scope.row.priceAmount) }}</template>
      </el-table-column>
      <el-table-column label="我的角色" width="90">
        <template #default="scope">{{ MY_ROLE_LABELS[scope.row.myRole ?? ''] ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="140">
        <template #default="scope">
          <el-tag :type="ORDER_STATUS_TAG_TYPES[scope.row.status] ?? 'info'">
            {{ ORDER_STATUS_LABELS[scope.row.status] ?? scope.row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="contractNo" label="关联合约号" width="120" />
      <el-table-column prop="createdAt" label="发起时间" width="180" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="scope">
          <RouterLink class="detail-link" :to="`/portal/orders/${scope.row.orderNo}`">查看详情</RouterLink>
        </template>
      </el-table-column>
      <template #empty>
        <span class="empty-tip">暂无订单</span>
      </template>
    </el-table>

    <el-pagination
      v-if="total > 0"
      class="list-pagination"
      layout="prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="pageNum"
      @current-change="onPageChange"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listMyOrders, type OrderSummary } from '../../api/order'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { formatPortalPricing, PRICING_MODEL_LABELS, labelOf } from '../../constants/catalog'
import {
  MY_ROLE_LABELS,
  ORDER_STATUS_LABELS,
  ORDER_STATUS_TAG_TYPES,
} from '../../constants/order'

/**
 * 我的订单列表（WBS-3.7.3 hifi §4.3；menuOrder 23 登录可见）：本方订单（需方/供方）
 * 状态过滤 + 分页 + 空态/错误态；只读——不做订单取消/修改（规格未定义订单侧动作，
 * B6-2 订单状态即权威，状态随合约走）；错误文案原样、认证失效经 guideToLoginIfAuthFailed 回登录。
 */
const router = useRouter()

const orders = ref<OrderSummary[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const statusFilter = ref('')
const loading = ref(false)
const errorTip = ref('')

async function loadOrders() {
  loading.value = true
  errorTip.value = ''
  try {
    const page = await listMyOrders({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      status: statusFilter.value || undefined,
    })
    orders.value = page.list
    total.value = page.total
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    errorTip.value = error instanceof Error ? error.message : String(error)
    orders.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function loadFirstPage() {
  pageNum.value = 1
  void loadOrders()
}

function onReset() {
  statusFilter.value = ''
  loadFirstPage()
}

function onPageChange(page: number) {
  pageNum.value = page
  void loadOrders()
}

onMounted(() => {
  void loadOrders()
})
</script>

<style scoped>
.order-list-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.back-link {
  font-size: 13px;
  color: var(--el-color-primary);
  text-decoration: none;
  width: fit-content;
}

.page-title {
  margin: 0;
  font-size: 20px;
}

.filter-bar {
  display: flex;
  gap: 10px;
}

.list-error {
  width: auto;
}

.empty-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.list-pagination {
  align-self: flex-end;
}

.detail-link {
  color: var(--el-color-primary);
  text-decoration: none;
  cursor: pointer;
}
</style>
