<template>
  <div class="bill-detail-page">
    <div v-if="notFound" class="not-found">
      <el-empty :description="BILL_NOT_FOUND_TIP" />
      <RouterLink class="back-btn" to="/billing">{{ BACK_TO_LIST_LABEL }}</RouterLink>
    </div>

    <template v-else>
      <div class="page-header">
        <span class="bill-no">{{ billNo }}</span>
        <el-tag v-if="detail" class="bill-type-tag" :type="detail.billType === 'CORRECTION' ? 'warning' : 'info'">
          {{ labelOf(BILL_TYPE_LABELS, detail.billType) }}
        </el-tag>
        <el-tag v-if="detail" class="bill-status" :type="BILL_STATUS_TAG[detail.status] ?? 'info'">
          {{ labelOf(BILL_STATUS_LABELS, detail.status) }}
        </el-tag>
        <span class="header-actions">
          <el-button
            type="primary"
            class="confirm-btn"
            :disabled="!canConfirm"
            @click="confirmVisible = true"
          >
            确认
          </el-button>
          <el-button class="correct-btn" :disabled="!canCorrect" @click="correctionVisible = true">更正</el-button>
          <el-button class="refresh-btn" :loading="loading" @click="loadDetail">刷新</el-button>
          <RouterLink class="back-btn" to="/billing">{{ BACK_TO_LIST_LABEL }}</RouterLink>
        </span>
      </div>

      <el-alert
        v-if="detail && detail.status === 'FROZEN'"
        class="frozen-tip"
        :title="FROZEN_CONFIRM_HINT"
        type="warning"
        :closable="false"
        show-icon
      />

      <el-alert
        v-if="errorTip"
        class="detail-error"
        :title="errorTip"
        type="error"
        :closable="false"
        show-icon
      >
        <template #default>
          <el-button link type="primary" class="retry-btn" @click="loadDetail">{{ RETRY_LABEL }}</el-button>
        </template>
      </el-alert>

      <BillDetailSections v-if="detail" :detail="detail" />

      <ConfirmDialog
        v-model="confirmVisible"
        :bill-no="billNo"
        @confirmed="onConfirmed"
        @failed="loadDetail"
      />
      <CorrectionDialog v-model="correctionVisible" :bill-no="billNo" @success="onCorrected" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import BillDetailSections from './components/BillDetailSections.vue'
import ConfirmDialog from './components/ConfirmDialog.vue'
import CorrectionDialog from './components/CorrectionDialog.vue'
import { getBillDetail, type BillDetail } from '../../api/billing'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { labelOf } from '../../constants/catalog'
import {
  BACK_TO_LIST_LABEL,
  BILL_NOT_FOUND_TIP,
  BILL_STATUS_LABELS,
  BILL_STATUS_TAG,
  BILL_TYPE_LABELS,
  FROZEN_CONFIRM_HINT,
  RETRY_LABEL,
} from '../../constants/billing'

/**
 * 运营账单详情深链（WBS-3.7.7 hifi §3 P3 / P4 / P5；route `/billing/bills/:billNo`）：
 * 快照 / 明细 / 合计对照 / 留痕 / 更正链（BillDetailSections 共用）+ 确认 / 更正写动作。
 * 状态门槛仅体验层（ISSUED 可确认、冻结全禁），服务端 1011C0011 / C0012 兜底；
 * 1011C0009 整页空态（同码同文防枚举）；自由文本零 `v-html`（DB-103 ②）。
 */
const route = useRoute()
const router = useRouter()

const billNo = computed(() => String(route.params.billNo ?? ''))
const detail = ref<BillDetail | null>(null)
const loading = ref(false)
const errorTip = ref('')
const notFound = ref(false)
const confirmVisible = ref(false)
const correctionVisible = ref(false)

const canConfirm = computed(() => detail.value?.status === 'ISSUED')
const canCorrect = computed(() => detail.value !== null && detail.value.status !== 'FROZEN')

async function loadDetail(): Promise<void> {
  loading.value = true
  errorTip.value = ''
  notFound.value = false
  try {
    detail.value = await getBillDetail(billNo.value)
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    detail.value = null
    if (error instanceof Error && (error as { code?: string }).code === '1011C0009') {
      notFound.value = true
      return
    }
    errorTip.value = error instanceof Error ? error.message : String(error)
  } finally {
    loading.value = false
  }
}

function onConfirmed(): void {
  confirmVisible.value = false
  ElMessage.success('账单已确认')
  void loadDetail()
}

function onCorrected(bill: BillDetail): void {
  correctionVisible.value = false
  ElMessage.success(`更正单已生成：${bill.billNo}`)
  void router.push(`/billing/bills/${encodeURIComponent(bill.billNo)}`)
}

onMounted(() => {
  void loadDetail()
})
</script>

<style scoped>
.bill-detail-page {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
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

.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-left: auto;
}

.back-btn {
  color: var(--el-color-primary);
  text-decoration: none;
  font-size: 13px;
}

.not-found {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
}
</style>
