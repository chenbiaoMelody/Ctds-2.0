<script setup lang="ts">
/**
 * WBS-3.7.2 页面 2：门户产品详情（hifi §3.2；深链路由 /portal/products/:productId）：
 * - 元数据 + 定价四档展示（R7 加性 priceAmount；免费档显式"免费"）；
 * - 动作条仅普通档：收藏 / 取消收藏 / 订阅 / 退订（W4~W7）；写后重拉状态（不做乐观更新）；
 * - 订购入口为禁用占位（Q3-A）：零请求、零订单；
 * - 不可达（1007C0011）→ 同一条提示常量，不区分不存在 / 未上架 / 已下架。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError, getDemoSubject } from '../../api/client'
import { getActorMode } from '../../stores/demoIdentity'
import {
  getProductDetail,
  favoriteProduct,
  unfavoriteProduct,
  subscribeProduct,
  unsubscribeProduct,
  listFavorites,
  listSubscriptions,
  type CatalogProductDetail,
  type FavoriteItem,
  type SubscriptionItem,
} from '../../api/catalog'
import {
  PRODUCT_NOT_ACCESSIBLE_TIP,
  PRICING_MODEL_LABELS,
  INTERACTION_ACTION_LABELS,
  labelOf,
  productStatusType,
  pricingModelTagType,
  formatPortalPricing,
} from '../../constants/catalog'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import PortalOrderDialog from './PortalOrderDialog.vue'
import {
  fetchEvaluationStats,
  fetchProductEvaluations,
  fetchMyEvaluation,
  submitEvaluation,
  hideEvaluation,
  type EvaluationItem,
  type EvaluationStats,
} from '../../api/review'
import {
  REVIEW_SECTION_TITLE,
  REVIEW_EMPTY_TIP,
  REVIEW_SCORE_LABEL,
  REVIEW_SCORE_INVALID_TIP,
  REVIEW_HIDE_CONFIRM_TEXT,
  REVIEW_SUBMIT_LABEL,
  REVIEW_UPDATE_LABEL,
  REVIEW_COMMENT_PLACEHOLDER,
  formatReviewMeta,
  formatReviewStats,
} from '../../constants/review'

const route = useRoute()
const router = useRouter()

const detail = ref<CatalogProductDetail | null>(null)
const unreachable = ref(false)
const loading = ref(false)
const favorited = ref(false)
const subscribed = ref(false)
const favoriteLoading = ref(false)
const subscriptionLoading = ref(false)

const isProviderMode = computed(() => getActorMode() !== 'operator')

/** 订购弹窗显隐（WBS-3.7.3 开放订购入口——hifi §4.1 仅改订购区，其余零改动）。 */
const orderDialogVisible = ref(false)

// ===== 产品评价区状态（WBS-3.7.4 hifi §5.2；B6：主动查询刷新，无推送/轮询） =====
const reviewStats = ref<EvaluationStats | null>(null)
const reviewList = ref<EvaluationItem[]>([])
const myReview = ref<EvaluationItem | null>(null)
const reviewScore = ref(0)
const reviewComment = ref('')
const reviewSubmitting = ref(false)
const reviewErrorTip = ref('')
const isOperatorMode = computed(() => getActorMode() === 'operator')

/** 评价区数据装载：统计 + 列表 + 本人回显（一屏闭环，Q3-A）。 */
async function loadReviewData(): Promise<void> {
  try {
    const [stats, list, mine] = await Promise.all([
      fetchEvaluationStats(productId()),
      fetchProductEvaluations(productId()),
      fetchMyEvaluation(productId()),
    ])
    reviewStats.value = stats
    reviewList.value = list.items
    myReview.value = mine
    reviewScore.value = mine?.score ?? 0
    reviewComment.value = mine?.comment ?? ''
  } catch (error) {
    showError(error)
  }
}

/** 提交/更新评价（W-EV1 upsert）：评分必选前端拦截（1011C0005 同义）+ 服务端兜底；
 * 拒绝文案业务可读（1011C0006 区块内展示——S2-10 负向演示路径）。 */
async function onSubmitReview(): Promise<void> {
  if (reviewScore.value < 1 || reviewScore.value > 5) {
    reviewErrorTip.value = REVIEW_SCORE_INVALID_TIP
    return
  }
  reviewSubmitting.value = true
  reviewErrorTip.value = ''
  try {
    await submitEvaluation({
      productId: productId(),
      score: reviewScore.value,
      comment: reviewComment.value.trim() || undefined,
      requestId: crypto.randomUUID(),
    })
    ElMessage.success(myReview.value ? '评价已更新' : '评价已提交')
    await loadReviewData()
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    reviewErrorTip.value = error instanceof ApiError ? error.message : '请求失败，请稍后重试'
  } finally {
    reviewSubmitting.value = false
  }
}

/** 运营隐藏（G-EV1）：确认弹窗固定文案 → 隐藏留痕不删除 → 重拉统计与列表（S2-12）。 */
async function onHideReview(item: EvaluationItem): Promise<void> {
  try {
    await ElMessageBox.confirm(REVIEW_HIDE_CONFIRM_TEXT, '隐藏评价', { type: 'warning' })
  } catch {
    return
  }
  try {
    await hideEvaluation(item.reviewId)
    ElMessage.success('已隐藏')
    await loadReviewData()
  } catch (error) {
    showError(error)
  }
}

function isOwnReview(item: EvaluationItem): boolean {
  return item.requesterNo === getDemoSubject()
}

function focusReviewForm(): void {
  document.querySelector('.review-form')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function onSubscribeOrder(): void {
  orderDialogVisible.value = true
}

function onOrderCreated(orderNo: string): void {
  orderDialogVisible.value = false
  void router.push(`/portal/orders/${orderNo}`)
}

function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error(error instanceof ApiError ? error.message : '请求失败，请稍后重试')
}

function parseProductId(): number | null {
  const raw = Array.isArray(route.params.productId) ? route.params.productId[0] : route.params.productId
  if (typeof raw !== 'string' || !/^\d+$/.test(raw)) return null
  const value = Number(raw)
  return value > 0 ? value : null
}

function productId(): number {
  return parseProductId() ?? 0
}

async function loadDetail(): Promise<void> {
  const id = parseProductId()
  if (id === null) {
    unreachable.value = true
    return
  }
  loading.value = true
  try {
    detail.value = await getProductDetail(id)
  } catch (error) {
    if (error instanceof ApiError && error.code === '1007C0011') {
      unreachable.value = true
      return
    }
    detail.value = null
    showError(error)
  } finally {
    loading.value = false
  }
}

async function loadFavoriteState(): Promise<void> {
  try {
    const page = await listFavorites({ pageNum: 1, pageSize: 100 })
    favorited.value = page.list.some((item: FavoriteItem) => item.productId === productId())
  } catch (error) {
    showError(error)
  }
}

async function loadSubscriptionState(): Promise<void> {
  try {
    const page = await listSubscriptions({ pageNum: 1, pageSize: 100 })
    subscribed.value = page.list.some((item: SubscriptionItem) => item.productId === productId())
  } catch (error) {
    showError(error)
  }
}

async function onFavorite(): Promise<void> {
  favoriteLoading.value = true
  try {
    await favoriteProduct(productId())
    ElMessage.success('已收藏')
    await loadFavoriteState()
  } catch (error) {
    showError(error)
  } finally {
    favoriteLoading.value = false
  }
}

async function onUnfavorite(): Promise<void> {
  favoriteLoading.value = true
  try {
    await unfavoriteProduct(productId())
    ElMessage.success(`${INTERACTION_ACTION_LABELS.UNFAVORITE}成功`)
    await loadFavoriteState()
  } catch (error) {
    showError(error)
  } finally {
    favoriteLoading.value = false
  }
}

async function onSubscribe(): Promise<void> {
  subscriptionLoading.value = true
  try {
    await subscribeProduct(productId())
    ElMessage.success('已订阅')
    await loadSubscriptionState()
  } catch (error) {
    showError(error)
  } finally {
    subscriptionLoading.value = false
  }
}

async function onUnsubscribe(): Promise<void> {
  subscriptionLoading.value = true
  try {
    await unsubscribeProduct(productId())
    ElMessage.success(`${INTERACTION_ACTION_LABELS.UNSUBSCRIBE}成功`)
    await loadSubscriptionState()
  } catch (error) {
    showError(error)
  } finally {
    subscriptionLoading.value = false
  }
}

onMounted(() => {
  void loadDetail()
  if (parseProductId() !== null) {
    void loadReviewData()
    if (isProviderMode.value) {
      void loadFavoriteState()
      void loadSubscriptionState()
    }
  }
})
</script>

<template>
  <div class="page">
    <router-link class="back-link" to="/portal">← 返回数据产品门户</router-link>

    <el-alert
      v-if="unreachable"
      class="not-accessible-tip"
      :title="PRODUCT_NOT_ACCESSIBLE_TIP"
      type="warning"
      show-icon
      :closable="false"
    />

    <el-card v-else shadow="never" v-loading="loading">
      <template v-if="detail">
        <div class="meta-head">
          <span class="product-name">{{ detail.productName }}</span>
          <el-tag :type="productStatusType(detail.status)">{{ detail.status }}</el-tag>
          <el-tag :type="pricingModelTagType(detail.pricingModel)" class="pricing-tag">
            {{ labelOf(PRICING_MODEL_LABELS, detail.pricingModel) }}
          </el-tag>
        </div>

        <el-descriptions :column="2" border class="meta-descriptions">
          <el-descriptions-item label="简介">{{ detail.intro ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="形态">{{ detail.productType }}</el-descriptions-item>
          <el-descriptions-item label="定价">
            <span class="pricing-value">{{ formatPortalPricing(labelOf(PRICING_MODEL_LABELS, detail.pricingModel), detail.priceAmount) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="类目">{{ detail.categoryName ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="提供方主体编号">{{ detail.providerSubjectNo }}</el-descriptions-item>
          <el-descriptions-item label="上架时间">{{ detail.listedAt ?? '—' }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="isProviderMode" class="action-bar">
          <el-button
            v-if="!favorited"
            type="primary"
            class="favorite-btn"
            :loading="favoriteLoading"
            @click="onFavorite"
          >
            {{ INTERACTION_ACTION_LABELS.FAVORITE }}
          </el-button>
          <el-button v-else class="unfavorite-btn" :loading="favoriteLoading" @click="onUnfavorite">
            {{ INTERACTION_ACTION_LABELS.UNFAVORITE }}
          </el-button>
          <el-button
            v-if="!subscribed"
            type="primary"
            plain
            class="subscribe-btn"
            :loading="subscriptionLoading"
            @click="onSubscribe"
          >
            {{ INTERACTION_ACTION_LABELS.SUBSCRIBE }}
          </el-button>
          <el-button v-else class="unsubscribe-btn" :loading="subscriptionLoading" @click="onUnsubscribe">
            {{ INTERACTION_ACTION_LABELS.UNSUBSCRIBE }}
          </el-button>
        </div>

        <div v-if="isProviderMode" class="order-area">
          <el-button class="order-btn" type="primary" @click="onSubscribeOrder">发起订购</el-button>
          <PortalOrderDialog
            v-if="orderDialogVisible"
            :product-id="parseProductId() ?? 0"
            :product-name="detail?.productName ?? ''"
            :pricing-model="detail?.pricingModel ?? ''"
            :price-amount="detail?.priceAmount ?? null"
            @close="orderDialogVisible = false"
            @ordered="onOrderCreated"
          />
        </div>

        <!-- 产品评价区（WBS-3.7.4 hifi §5.2；资格门槛在服务端，表单所有档可见可提交） -->
        <div class="review-section">
          <div class="review-title">{{ REVIEW_SECTION_TITLE }}</div>
          <div class="review-stats">
            <template v-if="reviewStats && reviewStats.averageScore != null">
              {{ formatReviewStats(reviewStats.averageScore, reviewStats.reviewCount) }}
            </template>
            <template v-else>{{ REVIEW_EMPTY_TIP }}</template>
          </div>
          <div class="review-list">
            <div v-for="item in reviewList" :key="item.reviewId" class="review-item">
              <el-rate :model-value="item.score" disabled class="review-stars" />
              <span v-if="item.comment" class="review-comment">{{ item.comment }}</span>
              <span class="review-meta">{{ formatReviewMeta(item.requesterNo, item.createdAt) }}</span>
              <el-button
                v-if="isOwnReview(item)"
                link
                type="primary"
                class="review-edit-btn"
                @click="focusReviewForm"
              >
                修改
              </el-button>
              <el-button
                v-if="isOperatorMode"
                link
                type="danger"
                class="review-hide-btn"
                @click="onHideReview(item)"
              >
                隐藏
              </el-button>
            </div>
            <div v-if="!reviewList.length" class="review-empty">{{ REVIEW_EMPTY_TIP }}</div>
          </div>
          <div v-if="reviewErrorTip" class="review-error-tip" role="alert">{{ reviewErrorTip }}</div>
          <div class="review-form">
            <div class="review-score-row">
              <span class="review-score-label">{{ REVIEW_SCORE_LABEL }}</span>
              <el-rate v-model="reviewScore" class="review-rate-input" />
            </div>
            <el-input
              v-model="reviewComment"
              type="textarea"
              :rows="3"
              :maxlength="500"
              show-word-limit
              :placeholder="REVIEW_COMMENT_PLACEHOLDER"
              class="review-comment-input"
            />
            <el-button
              type="primary"
              class="review-submit-btn"
              :loading="reviewSubmitting"
              @click="onSubmitReview"
            >
              {{ myReview ? REVIEW_UPDATE_LABEL : REVIEW_SUBMIT_LABEL }}
            </el-button>
          </div>
        </div>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.back-link {
  display: inline-block;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--el-color-primary);
  text-decoration: none;
}

.not-accessible-tip {
  margin-top: 8px;
}

.meta-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.product-name {
  font-size: 16px;
  font-weight: 600;
}

.action-bar {
  display: flex;
  gap: 8px;
  margin: 16px 0;
}

.order-area {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
}

.order-tip {
  font-size: 13px;
  color: #6b7280;
}

/* ===== 产品评价区（WBS-3.7.4 hifi §5.2；沿门户既有视觉模式，不新造组件） ===== */
.review-section {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
}

.review-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 8px;
}

.review-stats {
  font-size: 13px;
  color: #6b7280;
  margin-bottom: 12px;
}

.review-item {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 8px 0;
  border-bottom: 1px dashed #f0f0f0;
}

.review-comment {
  font-size: 13px;
  color: #374151;
}

.review-meta {
  font-size: 12px;
  color: #9ca3af;
}

.review-empty {
  font-size: 13px;
  color: #9ca3af;
  padding: 8px 0;
}

.review-error-tip {
  margin: 8px 0;
  padding: 8px 12px;
  font-size: 13px;
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border-radius: 4px;
}

.review-form {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}

.review-score-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.review-score-label {
  font-size: 13px;
  color: #6b7280;
}

.review-comment-input {
  width: 100%;
}
</style>
