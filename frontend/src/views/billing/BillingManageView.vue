<template>
  <div class="billing-manage-page">
    <h2 class="page-title">计费管理</h2>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="账单管理" name="bills">
        <div class="filter-bar">
          <el-input v-model="billFilters.contractNo" class="filter-contract" placeholder="合约号" clearable style="width: 150px" />
          <el-date-picker
            v-model="billFilters.period"
            class="filter-period"
            type="month"
            value-format="YYYY-MM"
            placeholder="账期"
            style="width: 130px"
          />
          <el-input v-model="billFilters.productId" class="filter-product" placeholder="产品 ID" clearable style="width: 110px" />
          <el-input v-model="billFilters.requesterNo" class="filter-requester" placeholder="需方主体号" clearable style="width: 170px" />
          <el-input v-model="billFilters.providerNo" class="filter-provider" placeholder="供方主体号" clearable style="width: 170px" />
          <el-select v-model="billFilters.status" class="filter-status" clearable placeholder="状态" style="width: 120px">
            <el-option
              v-for="(label, value) in BILL_STATUS_LABELS"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
          <el-select v-model="billFilters.billType" class="filter-bill-type" clearable placeholder="类型" style="width: 130px">
            <el-option
              v-for="(label, value) in BILL_TYPE_LABELS"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
          <el-button type="primary" class="bill-search-btn" :loading="billLoading" @click="searchBills">查询</el-button>
          <el-button class="bill-reset-btn" @click="resetBills">重置</el-button>
          <el-button type="primary" class="outbill-btn" @click="outbillVisible = true">手动出账</el-button>
        </div>

        <el-alert
          v-if="billError"
          class="list-error"
          :title="billError"
          type="error"
          :closable="false"
          show-icon
        >
          <template #default>
            <el-button link type="primary" class="retry-btn" @click="loadBills">重试</el-button>
          </template>
        </el-alert>

        <el-table v-loading="billLoading" :data="bills" class="bill-table" size="small">
          <el-table-column label="账单号" width="120">
            <template #default="scope">
              <RouterLink class="bill-link" :to="`/billing/bills/${scope.row.billNo}`">
                {{ scope.row.billNo }}
              </RouterLink>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="100">
            <template #default="scope">{{ labelOf(BILL_TYPE_LABELS, scope.row.billType) }}</template>
          </el-table-column>
          <el-table-column prop="contractNo" label="合约号" width="120" />
          <el-table-column prop="requesterNo" label="需方" width="160" show-overflow-tooltip />
          <el-table-column prop="providerNo" label="供方" width="160" show-overflow-tooltip />
          <el-table-column label="产品" min-width="160" show-overflow-tooltip>
            <template #default="scope">{{ scope.row.productName }}（{{ scope.row.productId }}）</template>
          </el-table-column>
          <el-table-column prop="period" label="账期" width="100" />
          <el-table-column label="定价档" width="110">
            <template #default="scope">{{ labelOf(PRICING_MODEL_LABELS, scope.row.pricingModel) }}</template>
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
            <span class="empty-tip">{{ BILL_EMPTY_TEXT }}</span>
          </template>
        </el-table>
        <el-pagination
          v-if="billTotal > billPageSize"
          class="bill-pagination"
          layout="total, prev, pager, next"
          :total="billTotal"
          :page-size="billPageSize"
          :current-page="billPageNum"
          @current-change="onBillPageChange"
        />
      </el-tab-pane>

      <el-tab-pane label="对账工作台" name="reconcile">
        <ReconcilePanel @view-bills="onViewBills" />
      </el-tab-pane>

      <el-tab-pane label="计量流水" name="metering">
        <div class="filter-bar">
          <el-input v-model="flowFilters.contractNo" class="metering-contract" placeholder="合约号" clearable style="width: 150px" />
          <el-input v-model="flowFilters.requesterNo" class="metering-requester" placeholder="主体号" clearable style="width: 170px" />
          <el-select v-model="flowFilters.sourceType" class="metering-source-type" clearable placeholder="源类型" style="width: 150px">
            <el-option
              v-for="(label, value) in SOURCE_TYPE_LABELS"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
          <el-date-picker
            v-model="flowFilters.range"
            class="metering-time-range"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm:ss"
            start-placeholder="起始时间"
            end-placeholder="结束时间"
            style="width: 340px"
          />
          <el-button type="primary" class="metering-search-btn" :loading="flowLoading" @click="searchFlows">查询</el-button>
          <el-button class="metering-reset-btn" @click="resetFlows">重置</el-button>
        </div>

        <el-alert class="metering-desc" :title="METERING_PAGE_DESC" type="info" :closable="false" />

        <el-alert
          v-if="flowError"
          class="metering-error"
          :title="flowError"
          type="error"
          :closable="false"
          show-icon
        />

        <el-table v-loading="flowLoading" :data="flows" class="metering-table" size="small">
          <el-table-column prop="id" label="流水号" width="90" />
          <el-table-column prop="contractNo" label="合约号" width="120" />
          <el-table-column prop="requesterNo" label="主体号" width="160" show-overflow-tooltip />
          <el-table-column label="源类型" width="140">
            <template #default="scope">{{ labelOf(SOURCE_TYPE_LABELS, scope.row.sourceType) }}</template>
          </el-table-column>
          <el-table-column prop="sourceNo" label="源单号" width="150" show-overflow-tooltip />
          <el-table-column prop="action" label="动作" width="200" show-overflow-tooltip />
          <el-table-column label="资源" min-width="180" show-overflow-tooltip>
            <template #default="scope">{{ scope.row.resource ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="occurredAt" label="事件时点" width="180" />
          <el-table-column prop="createdAt" label="入库时点" width="180" />
          <template #empty>
            <span class="empty-tip">{{ FLOW_EMPTY_TEXT }}</span>
          </template>
        </el-table>
        <el-pagination
          v-if="flowTotal > flowPageSize"
          class="metering-pagination"
          layout="total, prev, pager, next"
          :total="flowTotal"
          :page-size="flowPageSize"
          :current-page="flowPageNum"
          @current-change="onFlowPageChange"
        />
      </el-tab-pane>

      <el-tab-pane label="订单检索" name="orders">
        <div class="filter-bar">
          <el-select v-model="orderFilters.status" class="order-status" clearable placeholder="状态" style="width: 160px">
            <el-option
              v-for="(label, value) in ORDER_STATUS_LABELS"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
          <el-input v-model="orderFilters.productId" class="order-product" placeholder="产品 ID" clearable style="width: 110px" />
          <el-input v-model="orderFilters.contractNo" class="order-contract" placeholder="合约号" clearable style="width: 150px" />
          <el-input v-model="orderFilters.requesterNo" class="order-requester" placeholder="需方主体号" clearable style="width: 170px" />
          <el-button type="primary" class="order-search-btn" :loading="orderLoading" @click="searchOrders">查询</el-button>
          <el-button class="order-reset-btn" @click="resetOrders">重置</el-button>
        </div>

        <el-alert class="order-desc" :title="ORDER_PAGE_DESC" type="info" :closable="false" />

        <el-alert
          v-if="orderError"
          class="order-error"
          :title="orderError"
          type="error"
          :closable="false"
          show-icon
        />

        <el-table v-loading="orderLoading" :data="orders" class="order-table" size="small">
          <el-table-column prop="orderNo" label="订单号" width="120" />
          <el-table-column prop="productName" label="产品" min-width="160" show-overflow-tooltip />
          <el-table-column prop="requesterSubjectNo" label="需方" width="160" show-overflow-tooltip />
          <el-table-column label="定价档" width="110">
            <template #default="scope">{{ labelOf(PRICING_MODEL_LABELS, scope.row.pricingModel) }}</template>
          </el-table-column>
          <el-table-column label="金额" width="100">
            <template #default="scope">{{ scope.row.priceAmount ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="scope">
              <el-tag :type="ORDER_STATUS_TAG_TYPES[scope.row.status] ?? 'info'">
                {{ labelOf(ORDER_STATUS_LABELS, scope.row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="contractNo" label="合约号" width="120" />
          <el-table-column prop="createdAt" label="下单时点" width="180" />
          <template #empty>
            <span class="empty-tip">{{ ORDER_EMPTY_TEXT }}</span>
          </template>
        </el-table>
        <el-pagination
          v-if="orderTotal > orderPageSize"
          class="order-pagination"
          layout="total, prev, pager, next"
          :total="orderTotal"
          :page-size="orderPageSize"
          :current-page="orderPageNum"
          @current-change="onOrderPageChange"
        />
      </el-tab-pane>

      <el-tab-pane label="费率视图" name="rates">
        <div class="rates-pane">
          <div class="rate-cards">
            <el-card v-for="item in PRICING_MODEL_OPTIONS" :key="item.value" class="rate-card" shadow="never">
              <div class="rate-title">{{ item.label }}</div>
              <div class="rate-rule">{{ item.rule }}</div>
            </el-card>
          </div>
          <el-alert class="rate-source" :title="PRICE_NO_RETRO_HINT" type="info" :closable="false" />
          <RouterLink class="rate-catalog-link" to="/catalog/products">前往产品上架页变更定价</RouterLink>
        </div>
      </el-tab-pane>
    </el-tabs>

    <OutbillDialog v-model="outbillVisible" @success="onOutbillSuccess" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ReconcilePanel from './components/ReconcilePanel.vue'
import OutbillDialog from './components/OutbillDialog.vue'
import {
  listGovernanceBills,
  listGovernanceMeteringFlows,
  type BillSummary,
  type MeteringFlowRow,
} from '../../api/billing'
import { listGovernanceOrders, type OrderSummary } from '../../api/order'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { labelOf, PRICING_MODEL_LABELS } from '../../constants/catalog'
import {
  BILL_EMPTY_TEXT,
  BILL_STATUS_LABELS,
  BILL_STATUS_TAG,
  BILL_TYPE_LABELS,
  FLOW_EMPTY_TEXT,
  METERING_PAGE_DESC,
  ORDER_EMPTY_TEXT,
  ORDER_PAGE_DESC,
  PRICE_NO_RETRO_HINT,
  PRICING_MODEL_RULES,
  SOURCE_TYPE_LABELS,
  outbillSuccessTip,
} from '../../constants/billing'
import { ORDER_STATUS_LABELS, ORDER_STATUS_TAG_TYPES } from '../../constants/order'

/**
 * 运营「计费管理」页（WBS-3.7.7 hifi §3 P1 / P2 / P6 / P7 / P8 / P9；menuOrder 24）：
 * Tab1 账单管理（B-R3 七维过滤 + 分页 + 手动出账）/ Tab2 对账工作台（ReconcilePanel）/
 * Tab3 计量流水（R-M4 新增全量读面）/ Tab4 订单检索（R-OR3 既有端点，零后端改动）/
 * Tab5 费率视图（只读口径说明 + 目录指路，Q2-A）。
 * 前端不做业务判定：状态门槛、金额校验、费率档位、归属一律以后端为准；
 * 服务端文案原样展示；不做乐观更新（写动作成功后重新拉取）；不做假幂等（按钮 in-flight 禁用仅防误触）。
 */
const router = useRouter()
const activeTab = ref('bills')

const PRICING_MODEL_OPTIONS = Object.entries(PRICING_MODEL_LABELS).map(([value, label]) => ({
  value,
  label,
  rule: PRICING_MODEL_RULES[value] ?? '',
}))

// ===== Tab1 账单管理 =====
const bills = ref<BillSummary[]>([])
const billTotal = ref(0)
const billPageNum = ref(1)
const billPageSize = 10
const billLoading = ref(false)
const billError = ref('')
const billFilters = reactive({
  contractNo: '',
  period: null as string | null,
  productId: '',
  requesterNo: '',
  providerNo: '',
  status: '',
  billType: '',
})
const outbillVisible = ref(false)

// ===== Tab3 计量流水 =====
const flows = ref<MeteringFlowRow[]>([])
const flowTotal = ref(0)
const flowPageNum = ref(1)
const flowPageSize = 10
const flowLoading = ref(false)
const flowError = ref('')
const flowLoaded = ref(false)
const flowFilters = reactive({
  contractNo: '',
  requesterNo: '',
  sourceType: '',
  range: null as [string, string] | null,
})

// ===== Tab4 订单检索 =====
const orders = ref<OrderSummary[]>([])
const orderTotal = ref(0)
const orderPageNum = ref(1)
const orderPageSize = 10
const orderLoading = ref(false)
const orderError = ref('')
const orderLoaded = ref(false)
const orderFilters = reactive({
  status: '',
  productId: '',
  contractNo: '',
  requesterNo: '',
})

function numberOrUndefined(value: string): number | undefined {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : undefined
}

function showError(error: unknown): string {
  if (guideToLoginIfAuthFailed(error, router)) return ''
  return error instanceof Error ? error.message : String(error)
}

async function loadBills(): Promise<void> {
  billLoading.value = true
  billError.value = ''
  try {
    const page = await listGovernanceBills({
      pageNum: billPageNum.value,
      pageSize: billPageSize,
      contractNo: billFilters.contractNo.trim() || undefined,
      period: billFilters.period ?? undefined,
      productId: numberOrUndefined(billFilters.productId),
      requesterNo: billFilters.requesterNo.trim() || undefined,
      providerNo: billFilters.providerNo.trim() || undefined,
      status: billFilters.status || undefined,
      billType: billFilters.billType || undefined,
    })
    bills.value = page.list
    billTotal.value = page.total
  } catch (error) {
    bills.value = []
    billTotal.value = 0
    billError.value = showError(error)
  } finally {
    billLoading.value = false
  }
}

function searchBills(): void {
  billPageNum.value = 1
  void loadBills()
}

function resetBills(): void {
  billFilters.contractNo = ''
  billFilters.period = null
  billFilters.productId = ''
  billFilters.requesterNo = ''
  billFilters.providerNo = ''
  billFilters.status = ''
  billFilters.billType = ''
  searchBills()
}

function onBillPageChange(page: number): void {
  billPageNum.value = page
  void loadBills()
}

function onOutbillSuccess(billNo: string): void {
  outbillVisible.value = false
  ElMessage.success(outbillSuccessTip(billNo))
  void router.push(`/billing/bills/${billNo}`)
}

// ===== Tab3 载入 =====
async function loadFlows(): Promise<void> {
  flowLoading.value = true
  flowError.value = ''
  try {
    const page = await listGovernanceMeteringFlows({
      pageNum: flowPageNum.value,
      pageSize: flowPageSize,
      contractNo: flowFilters.contractNo.trim() || undefined,
      requesterNo: flowFilters.requesterNo.trim() || undefined,
      sourceType: flowFilters.sourceType || undefined,
      from: flowFilters.range?.[0],
      to: flowFilters.range?.[1],
    })
    flows.value = page.list
    flowTotal.value = page.total
  } catch (error) {
    flows.value = []
    flowTotal.value = 0
    flowError.value = showError(error)
  } finally {
    flowLoading.value = false
    flowLoaded.value = true
  }
}

function searchFlows(): void {
  flowPageNum.value = 1
  void loadFlows()
}

function resetFlows(): void {
  flowFilters.contractNo = ''
  flowFilters.requesterNo = ''
  flowFilters.sourceType = ''
  flowFilters.range = null
  searchFlows()
}

function onFlowPageChange(page: number): void {
  flowPageNum.value = page
  void loadFlows()
}

// ===== Tab4 载入 =====
async function loadOrders(): Promise<void> {
  orderLoading.value = true
  orderError.value = ''
  try {
    const page = await listGovernanceOrders({
      pageNum: orderPageNum.value,
      pageSize: orderPageSize,
      status: orderFilters.status || undefined,
      productId: numberOrUndefined(orderFilters.productId),
      contractNo: orderFilters.contractNo.trim() || undefined,
      requesterNo: orderFilters.requesterNo.trim() || undefined,
    })
    orders.value = page.list
    orderTotal.value = page.total
  } catch (error) {
    orders.value = []
    orderTotal.value = 0
    orderError.value = showError(error)
  } finally {
    orderLoading.value = false
    orderLoaded.value = true
  }
}

function searchOrders(): void {
  orderPageNum.value = 1
  void loadOrders()
}

function resetOrders(): void {
  orderFilters.status = ''
  orderFilters.productId = ''
  orderFilters.contractNo = ''
  orderFilters.requesterNo = ''
  searchOrders()
}

function onOrderPageChange(page: number): void {
  orderPageNum.value = page
  void loadOrders()
}

/** 对账工作台「查看该期账单」：切 Tab1 并带入合约号 + 账期过滤（hifi §3 P6）。 */
function onViewBills(payload: { contractNo: string; period: string }): void {
  billFilters.contractNo = payload.contractNo
  billFilters.period = payload.period || null
  activeTab.value = 'bills'
  searchBills()
}

watch(activeTab, (tab) => {
  if (tab === 'metering' && !flowLoaded.value) {
    void loadFlows()
  }
  if (tab === 'orders' && !orderLoaded.value) {
    void loadOrders()
  }
})

onMounted(() => {
  void loadBills()
})
</script>

<style scoped>
.billing-manage-page {
  padding: 16px;
}

.page-title {
  margin: 0 0 12px;
  font-size: 20px;
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.empty-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.bill-pagination,
.metering-pagination,
.order-pagination {
  margin-top: 10px;
  justify-content: flex-end;
}

.metering-desc,
.order-desc {
  margin-bottom: 10px;
}

.rate-cards {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}

.rate-card {
  width: 240px;
}

.rate-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 6px;
}

.rate-rule {
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.5;
}

.rate-source {
  margin-bottom: 12px;
}

.rate-catalog-link {
  color: var(--el-color-primary);
  text-decoration: none;
  font-size: 13px;
}
</style>
