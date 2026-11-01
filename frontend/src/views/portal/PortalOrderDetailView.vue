<template>
  <div class="order-detail-page" v-loading="loading">
    <RouterLink class="back-link" to="/portal/orders">← 返回我的订单</RouterLink>

    <template v-if="detail">
      <div class="page-head">
        <h2 class="page-title">订单 {{ detail.orderNo }}</h2>
        <el-tag :type="ORDER_STATUS_TAG_TYPES[detail.status] ?? 'info'">
          {{ ORDER_STATUS_LABELS[detail.status] ?? detail.status }}
        </el-tag>
      </div>

      <el-card class="detail-card" shadow="never">
        <template #header>基本信息</template>
        <div class="info-grid">
          <div class="info-item"><span class="info-label">产品名称</span><span>{{ detail.productName }}</span></div>
          <div class="info-item"><span class="info-label">需方</span><span>{{ detail.requesterSubjectNo }}</span></div>
          <div class="info-item"><span class="info-label">供方</span><span>{{ detail.providerSubjectNo }}</span></div>
          <div class="info-item"><span class="info-label">定价</span><span>{{ pricingText }}</span></div>
          <div class="info-item">
            <span class="info-label">合约模板</span>
            <span>{{ detail.templateNo }}（V{{ detail.templateVersionNo }}）</span>
          </div>
          <div class="info-item"><span class="info-label">发起时间</span><span>{{ detail.createdAt }}</span></div>
          <div class="info-item">
            <span class="info-label">关联合约</span>
            <span>
              {{ detail.contractNo }}
              <RouterLink
                class="contract-workbench-link"
                :to="`/contracts/workbench/${detail.contractNo}`"
              >前往合约工作台</RouterLink>
            </span>
          </div>
        </div>
      </el-card>

      <el-card class="detail-card" shadow="never">
        <template #header>状态</template>
        <div class="info-grid">
          <div class="info-item">
            <span class="info-label">订单状态</span>
            <span>{{ ORDER_STATUS_LABELS[detail.status] ?? detail.status }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">关联合约状态</span>
            <span>{{ detail.contractStatus ? (CONTRACT_STATUS_LABELS[detail.contractStatus] ?? detail.contractStatus) : '—' }}</span>
          </div>
          <div v-if="detail.status === 'CLOSED'" class="info-item">
            <span class="info-label">关闭原因</span><span>{{ detail.closedReason ?? '—' }}</span>
          </div>
          <div v-if="detail.status === 'CLOSED'" class="info-item">
            <span class="info-label">关闭时间</span><span>{{ detail.closedAt ?? '—' }}</span>
          </div>
        </div>
      </el-card>

      <el-card class="detail-card" shadow="never">
        <template #header>订单时间线</template>
        <el-timeline v-if="visibleActions.length > 0" class="order-timeline">
          <el-timeline-item
            v-for="(action, index) in visibleActions"
            :key="index"
            :timestamp="action.createdAt"
          >
            <b>{{ ORDER_ACTION_LABELS[action.action] ?? action.action }}</b>
            <span v-if="action.toStatus" class="timeline-status">
              （{{ ORDER_STATUS_LABELS[action.toStatus] ?? action.toStatus }}）
            </span>
            <span v-if="action.reason" class="timeline-reason">— {{ action.reason }}</span>
          </el-timeline-item>
        </el-timeline>
        <span v-else class="empty-tip">暂无留痕</span>
      </el-card>
    </template>

    <el-alert v-else-if="notFoundTip" class="detail-error" :title="notFoundTip" type="error" :closable="false" show-icon />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getOrderDetail, type OrderDetail } from '../../api/order'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { formatPortalPricing, PRICING_MODEL_LABELS, labelOf } from '../../constants/catalog'
import { CONTRACT_STATUS_LABELS } from '../../constants/contract'
import {
  ORDER_ACTION_LABELS,
  ORDER_NOT_FOUND_TIP,
  ORDER_STATUS_LABELS,
  ORDER_STATUS_TAG_TYPES,
} from '../../constants/order'

/**
 * 订单详情（WBS-3.7.3 hifi §4.4）：快照 / 状态（订单 + 关联合约）/ 留痕时间线 /
 * 关联合约工作台深链；已关闭展示关闭原因与关闭时间；非本方 / 不存在 / 非法订单号同形
 * ORDER_NOT_FOUND_TIP（非法编号前端前置校验零请求——OD+6 位；服务端同形兜底）；
 * 不做评价 / 账单 / 支付入口（规格边界）。
 */
const route = useRoute()
const router = useRouter()

const detail = ref<OrderDetail | null>(null)
const loading = ref(false)
const notFoundTip = ref('')

/** 订单号形态前置校验（OD + 6 位数字——非法参数零请求）。 */
const isWellFormedOrderNo = computed(() => /^OD\d{6}$/.test(String(route.params.orderNo ?? '')))

const pricingText = computed(() =>
  detail.value
    ? formatPortalPricing(labelOf(PRICING_MODEL_LABELS, detail.value.pricingModel), detail.value.priceAmount)
    : '—')

/** 时间线可见行（纵深防御：DENIED_ACCESS 拒绝留痕不进用户读面——服务端已过滤，前端兜底）。 */
const visibleActions = computed(() =>
  (detail.value?.actions ?? []).filter((action) => action.action !== 'DENIED_ACCESS'))

async function loadDetail() {
  if (!isWellFormedOrderNo.value) {
    notFoundTip.value = ORDER_NOT_FOUND_TIP
    return
  }
  loading.value = true
  notFoundTip.value = ''
  try {
    detail.value = await getOrderDetail(String(route.params.orderNo))
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) {
      return
    }
    // 非本方 / 不存在 / 其他失败：详情页同形提示（1011C0002 文案与后端逐字一致）
    notFoundTip.value = error instanceof Error ? error.message : String(error)
    detail.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadDetail()
})
</script>

<style scoped>
.order-detail-page {
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

.page-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-title {
  margin: 0;
  font-size: 20px;
}

.detail-card {
  width: 100%;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 24px;
}

.info-item {
  display: flex;
  gap: 8px;
  font-size: 14px;
}

.info-label {
  color: var(--el-text-color-secondary);
  min-width: 88px;
}

.contract-workbench-link {
  color: var(--el-color-primary);
  text-decoration: none;
  margin-left: 8px;
}

.timeline-status {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.timeline-reason {
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.detail-error {
  width: auto;
}

.empty-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
