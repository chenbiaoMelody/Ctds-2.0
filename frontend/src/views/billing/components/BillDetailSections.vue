<template>
  <div class="bill-detail-sections">
    <section class="snapshot">
      <h3 class="section-title">账单快照</h3>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="合约号">{{ detail.contractNo }}</el-descriptions-item>
        <el-descriptions-item label="账期">{{ detail.period }}</el-descriptions-item>
        <el-descriptions-item label="需方">{{ detail.requesterNo }}</el-descriptions-item>
        <el-descriptions-item label="供方">{{ detail.providerNo }}</el-descriptions-item>
        <el-descriptions-item label="产品">{{ detail.productName }}（ID：{{ detail.productId }}）</el-descriptions-item>
        <el-descriptions-item label="定价档">{{ labelOf(PRICING_MODEL_LABELS, detail.pricingModel) }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.quantity !== null" label="数量">{{ detail.quantity }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.unitPrice !== null" label="单价">{{ detail.unitPrice }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.baseAmount !== null" label="约定交易额基数">
          {{ detail.baseAmount }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.shareRate !== null" label="分成费率">{{ detail.shareRate }}</el-descriptions-item>
        <el-descriptions-item label="账单金额">{{ detail.amount }}</el-descriptions-item>
        <el-descriptions-item label="生成时点">{{ detail.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="生成人">{{ creator }}</el-descriptions-item>
      </el-descriptions>
    </section>

    <section class="details">
      <h3 class="section-title">账单明细</h3>
      <el-table :data="detail.details" class="detail-table" size="small">
        <el-table-column prop="lineNo" label="行号" width="70" />
        <el-table-column label="类型" width="110">
          <template #default="scope">{{ labelOf(DETAIL_LINE_TYPE_LABELS, scope.row.lineType) }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="140">
          <template #default="scope">{{ scope.row.description ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="计量流水号" width="110">
          <template #default="scope">{{ scope.row.meteringFlowId ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="源类型" width="130">
          <template #default="scope">{{ labelOf(SOURCE_TYPE_LABELS, scope.row.sourceType) }}</template>
        </el-table-column>
        <el-table-column label="源单号" min-width="120">
          <template #default="scope">{{ scope.row.sourceNo ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="事件时点" width="170">
          <template #default="scope">{{ scope.row.occurredAt ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="数量" width="80">
          <template #default="scope">{{ scope.row.quantity ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="单价" width="90">
          <template #default="scope">{{ scope.row.unitPrice ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="金额" width="100">
          <template #default="scope">{{ scope.row.amount ?? '—' }}</template>
        </el-table-column>
        <template #empty>
          <span class="empty-tip">暂无明细</span>
        </template>
      </el-table>
      <div class="total-compare">
        <span class="detail-sum">明细合计：{{ detailSum }}</span>
        <span class="bill-amount">账单金额：{{ detail.amount }}</span>
        <span v-if="sumMismatch" class="total-mismatch">{{ DETAIL_SUM_DISCLAIMER }}</span>
      </div>
    </section>

    <section class="actions">
      <h3 class="section-title">动作留痕</h3>
      <el-table :data="detail.actions" class="action-log-table" size="small">
        <el-table-column label="动作" width="90">
          <template #default="scope">{{ labelOf(BILL_ACTION_LABELS, scope.row.action) }}</template>
        </el-table-column>
        <el-table-column label="原状态→新状态" width="180">
          <template #default="scope">
            {{ labelOf(BILL_STATUS_LABELS, scope.row.fromStatus) }}→{{ labelOf(BILL_STATUS_LABELS, scope.row.toStatus) }}
          </template>
        </el-table-column>
        <el-table-column label="原因" min-width="180">
          <!-- 自由文本一律模板插值转义（零 v-html，DB-103 ②） -->
          <template #default="scope">{{ scope.row.reason ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="160">
          <template #default="scope">{{ scope.row.operatorSubjectNo ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时点" width="180" />
        <template #empty>
          <span class="empty-tip">暂无留痕</span>
        </template>
      </el-table>
    </section>

    <section class="correction-chain">
      <h3 class="section-title">更正链</h3>
      <div v-if="detail.correctionOfBillNo" class="chain-row">
        <span>本单为更正单，</span>
        <RouterLink class="chain-link" :to="`${basePath}/${detail.correctionOfBillNo}`">返回原单</RouterLink>
        <span>（{{ detail.correctionOfBillNo }}）</span>
      </div>
      <div v-else-if="detail.correctionBillNos.length > 0" class="chain-row">
        <span>本单的更正单：</span>
        <RouterLink
          v-for="billNo in detail.correctionBillNos"
          :key="billNo"
          class="chain-link"
          :to="`${basePath}/${billNo}`"
        >
          {{ billNo }}
        </RouterLink>
      </div>
      <div v-else class="chain-row">暂无更正记录</div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { BillDetail } from '../../../api/billing'
import { labelOf, PRICING_MODEL_LABELS } from '../../../constants/catalog'
import {
  BILL_ACTION_LABELS,
  BILL_STATUS_LABELS,
  DETAIL_LINE_TYPE_LABELS,
  DETAIL_SUM_DISCLAIMER,
  SOURCE_TYPE_LABELS,
} from '../../../constants/billing'

/**
 * 账单详情分区（WBS-3.7.7 hifi §2.4 / §3 P3·P11；运营与门户共用）：
 * 快照 + 明细逐行 + 明细合计对照 + 动作留痕 + 更正链。
 * 合计仅为前端算术对照（Q6-A）：不一致时并列提示「以服务端为准」，不拦截、不改值、不重算引擎；
 * 自由文本零 `v-html`（DB-103 ②）；写动作区不在本组件（运营详情头区承载，门户零写动作）。
 */
const props = withDefaults(defineProps<{ detail: BillDetail; basePath?: string }>(), {
  basePath: '/billing/bills',
})

/** 生成人 = 创建留痕操作人（账单行无独立生成人列；留痕缺失时显示占位）。 */
const creator = computed(() => {
  const created = props.detail.actions.find((action) => action.action === 'CREATE')
  return created?.operatorSubjectNo ?? '—'
})

/** 明细合计（两位小数定点，避免浮点累加误差；仅展示用）。 */
const detailSumCents = computed(() =>
  props.detail.details.reduce((sum, line) => sum + toCents(line.amount), 0))

const detailSum = computed(() => (detailSumCents.value / 100).toFixed(2))

const sumMismatch = computed(() => detailSumCents.value !== toCents(props.detail.amount))

function toCents(amount: string | null | undefined): number {
  const value = Number(amount ?? 0)
  return Number.isFinite(value) ? Math.round(value * 100) : 0
}
</script>

<style scoped>
.bill-detail-sections {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.section-title {
  margin: 0 0 8px;
  font-size: 15px;
}

.total-compare {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-top: 10px;
  font-size: 13px;
}

.total-mismatch {
  color: var(--el-color-danger);
}

.chain-row {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 13px;
}

.chain-link {
  color: var(--el-color-primary);
  text-decoration: none;
}

.empty-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
