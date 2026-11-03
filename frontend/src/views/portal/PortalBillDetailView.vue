<template>
  <div class="portal-bill-detail-page">
    <RouterLink class="back-btn" to="/portal/bills">← 返回账单列表</RouterLink>

    <div v-if="notFound" class="not-found">
      <el-empty :description="BILL_NOT_FOUND_TIP" />
    </div>

    <template v-else>
      <div class="page-header">
        <span class="bill-no">{{ billNo }}</span>
        <el-tag v-if="detail" class="bill-status" :type="BILL_STATUS_TAG[detail.status] ?? 'info'">
          {{ labelOf(BILL_STATUS_LABELS, detail.status) }}
        </el-tag>
      </div>

      <el-alert v-if="errorTip" class="detail-error" :title="errorTip" type="error" :closable="false" show-icon />

      <BillDetailSections v-if="detail" :detail="detail" base-path="/portal/bills" />

      <section v-if="detail" class="reconcile-section">
        <h3 class="section-title">对账结论</h3>
        <el-alert
          v-if="reconcileError"
          class="reconcile-error"
          :title="reconcileError"
          type="info"
          :closable="false"
        />
        <el-table v-else :data="records" class="reconcile-table" size="small">
          <el-table-column prop="period" label="账期" width="100" />
          <el-table-column label="结论" width="100">
            <template #default="scope">
              <el-tag :type="CONCLUSION_TAG[scope.row.conclusion] ?? 'info'">
                {{ labelOf(CONCLUSION_LABELS, scope.row.conclusion) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="差异明细" min-width="260">
            <template #default="scope">
              <span v-if="scope.row.differences.length === 0">{{ DIFFERENCE_EMPTY_TEXT }}</span>
              <template v-else>
                <span
                  v-for="item in scope.row.differences"
                  :key="item.type + item.leg"
                  class="difference-item"
                >
                  {{ labelOf(DIFFERENCE_TYPE_LABELS, item.type) }}（{{ item.leg ?? '—' }}）：{{
                    item.detail ?? `${item.expected} / ${item.actual}` }}
                </span>
              </template>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="执行时点" width="180" />
          <template #empty>
            <span class="empty-tip">暂无对账记录</span>
          </template>
        </el-table>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BillDetailSections from '../billing/components/BillDetailSections.vue'
import { getBillDetail, listReconciliations, type BillDetail, type Reconciliation } from '../../api/billing'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { labelOf } from '../../constants/catalog'
import {
  BILL_NOT_FOUND_TIP,
  BILL_STATUS_LABELS,
  BILL_STATUS_TAG,
  CONCLUSION_LABELS,
  CONCLUSION_TAG,
  DIFFERENCE_EMPTY_TEXT,
  DIFFERENCE_TYPE_LABELS,
} from '../../constants/billing'

/**
 * 门户账单详情（WBS-3.7.7 hifi §3 P11；route `/portal/bills/:billNo`）：
 * **零写动作**（无确认 / 更正 / 出账入口）；快照 + 明细 + 合计对照 + 更正链与运营详情同构
 * （共用 BillDetailSections，写动作区不引入）；对账结论区只读（hifi §8 账单核对 S1-3：
 * 需方 / 供方可读本方合约结论与差异明细，B-R4 `billing.read` + 服务端归属校验）。
 * 1011C0009 整页空态「账单不存在或不可读」（同码同文，不区分不存在与非本方）。
 */
const route = useRoute()
const router = useRouter()

const billNo = computed(() => String(route.params.billNo ?? ''))
const detail = ref<BillDetail | null>(null)
const records = ref<Reconciliation[]>([])
const reconcileError = ref('')
const errorTip = ref('')
const notFound = ref(false)

async function loadDetail(): Promise<void> {
  errorTip.value = ''
  notFound.value = false
  try {
    detail.value = await getBillDetail(billNo.value)
    await loadReconciliations()
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    detail.value = null
    if (error instanceof Error && (error as { code?: string }).code === '1011C0009') {
      notFound.value = true
      return
    }
    errorTip.value = error instanceof Error ? error.message : String(error)
  }
}

/** 对账结论为附列读面：失败不影响账单主体，仅该区提示（不伪造结论）。 */
async function loadReconciliations(): Promise<void> {
  reconcileError.value = ''
  if (!detail.value) return
  try {
    const page = await listReconciliations(detail.value.contractNo, { pageNum: 1, pageSize: 5 })
    records.value = page.list
  } catch (error) {
    records.value = []
    reconcileError.value = error instanceof Error ? error.message : String(error)
  }
}

onMounted(() => {
  void loadDetail()
})
</script>

<style scoped>
.portal-bill-detail-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.back-btn {
  color: var(--el-color-primary);
  text-decoration: none;
  font-size: 13px;
  width: fit-content;
}

.page-header {
  display: flex;
  gap: 10px;
  align-items: center;
}

.bill-no {
  font-size: 18px;
  font-weight: 600;
}

.section-title {
  margin: 0 0 8px;
  font-size: 15px;
}

.difference-item {
  display: block;
  font-size: 12px;
}

.empty-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.not-found {
  display: flex;
  justify-content: center;
}
</style>
