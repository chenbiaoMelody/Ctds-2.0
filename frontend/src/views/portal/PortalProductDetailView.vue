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
import { ElMessage } from 'element-plus'
import { ApiError } from '../../api/client'
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
  PORTAL_ORDER_PLACEHOLDER_TIP,
  PRICING_MODEL_LABELS,
  INTERACTION_ACTION_LABELS,
  labelOf,
  productStatusType,
  pricingModelTagType,
  formatPortalPricing,
} from '../../constants/catalog'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'

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
  if (isProviderMode.value && parseProductId() !== null) {
    void loadFavoriteState()
    void loadSubscriptionState()
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
            <span class="pricing-value">{{ formatPortalPricing(detail.pricingModel, detail.priceAmount) }}</span>
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
          <el-button class="order-btn" type="primary" disabled>发起订购</el-button>
          <span class="order-tip">{{ PORTAL_ORDER_PLACEHOLDER_TIP }}</span>
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
</style>
