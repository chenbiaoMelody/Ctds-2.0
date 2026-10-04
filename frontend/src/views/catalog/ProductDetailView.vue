<script setup lang="ts">
/**
 * WBS-3.3.6 页面 2：产品详情（hifi §6.3 / 测试锚点 T6/T7/T10；深链路由 /catalog/products/:productId）：
 * - 元数据全量 + 状态 tag；**无任何数据本体内容段落**（检索 ≠ 可访问）；
 * - 动作条：收藏/取消收藏、订阅/退订（按本人当前状态显隐；重复操作幂等——以响应为准刷新）；
 *   运营档不显示动作条（体验层，服务端仍为判定源 catalog.interact）；
 * - 变更记录区：先取本人订阅列表判断是否已订阅（体验层），已订阅才加载 R8；
 * - 不可达分支（非在架/不存在）：同一条提示常量与样式，不区分"不存在/未上架/已下架/已注销"。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../api/client'
import { getActorMode } from '../../stores/demoIdentity'
import {
  getProductDetail,
  getProductChangeLogs,
  favoriteProduct,
  unfavoriteProduct,
  subscribeProduct,
  unsubscribeProduct,
  listFavorites,
  listSubscriptions,
  type CatalogProductDetail,
  type ProductChangeLog,
  type FavoriteItem,
  type SubscriptionItem,
} from '../../api/catalog'
import {
  PRODUCT_NOT_ACCESSIBLE_TIP,
  CHANGE_LOGS_SUBSCRIPTION_REQUIRED_TIP,
  INTERACTION_ACTION_LABELS,
} from '../../constants/catalog'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'

const route = useRoute()
const router = useRouter()

const detail = ref<CatalogProductDetail | null>(null)
const unreachable = ref(false)
const loading = ref(false)
const changeLogs = ref<ProductChangeLog[]>([])
const changeLogTotal = ref(0)
const changeLogPageNum = ref(1)
const changeLogLoading = ref(false)
const favorited = ref(false)
const subscribed = ref(false)

const isProviderMode = computed(() => getActorMode() !== 'operator')

function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error(error instanceof ApiError ? error.message : '请求失败，请稍后重试')
}

function productId(): number {
  return Number(route.params.productId)
}

async function loadDetail(): Promise<void> {
  loading.value = true
  try {
    detail.value = await getProductDetail(productId())
  } catch (error) {
    if (error instanceof ApiError && error.code === '1007C0011') {
      // 不可达分支：非本人 / 未上架 / 已下架 / 已注销 / 不存在 → 同一条提示常量（防枚举）
      unreachable.value = true
      return
    }
    showError(error)
  } finally {
    loading.value = false
  }
}

async function loadInteractionState(): Promise<void> {
  try {
    const favoritePage = await listFavorites(1, 100)
    favorited.value = favoritePage.list.some((item: FavoriteItem) => item.productId === productId())
  } catch (error) {
    showError(error)
  }
  try {
    const subscriptionPage = await listSubscriptions(1, 100)
    subscribed.value = subscriptionPage.list.some((item: SubscriptionItem) => item.productId === productId())
  } catch (error) {
    showError(error)
  }
}

async function loadChangeLogs(): Promise<void> {
  changeLogLoading.value = true
  try {
    const page = await getProductChangeLogs(productId(), changeLogPageNum.value, 10)
    changeLogs.value = page.list
    changeLogTotal.value = page.total
  } catch (error) {
    showError(error)
  } finally {
    changeLogLoading.value = false
  }
}

async function onFavorite(): Promise<void> {
  try {
    await favoriteProduct(productId())
    ElMessage.success('已收藏')
    await loadInteractionState()
  } catch (error) {
    showError(error)
  }
}

async function onUnfavorite(): Promise<void> {
  try {
    await unfavoriteProduct(productId())
    ElMessage.success(`${INTERACTION_ACTION_LABELS.UNFAVORITE}成功`)
    await loadInteractionState()
  } catch (error) {
    showError(error)
  }
}

async function onSubscribe(): Promise<void> {
  try {
    await subscribeProduct(productId())
    ElMessage.success('已订阅')
    await loadInteractionState()
    await loadChangeLogs()
  } catch (error) {
    showError(error)
  }
}

async function onUnsubscribe(): Promise<void> {
  try {
    await unsubscribeProduct(productId())
    ElMessage.success(`${INTERACTION_ACTION_LABELS.UNSUBSCRIBE}成功`)
    await loadInteractionState()
  } catch (error) {
    showError(error)
  }
}

function changeChangeLogPage(page: number): void {
  changeLogPageNum.value = page
  void loadChangeLogs()
}

onMounted(() => {
  void loadDetail()
  void loadInteractionState().then(() => {
    if (subscribed.value) void loadChangeLogs()
  })
})
</script>

<template>
  <div class="page">
    <h2 class="page-title">产品详情</h2>
    <p class="page-desc">仅目录元数据展示（检索 ≠ 可访问；不含敏感原文与资源内容）。</p>

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
          <el-tag>{{ detail.status }}</el-tag>
        </div>

        <el-descriptions :column="2" border class="meta-descriptions">
          <!-- 字段清单 = hifi §6.3；R7 出参无 priceAmount（3.3.4 既有契约）→ 不得展示"价格数值"行 -->
          <el-descriptions-item label="简介">{{ detail.intro ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="形态">{{ detail.productType }}</el-descriptions-item>
          <el-descriptions-item label="定价模型">{{ detail.pricingModel }}</el-descriptions-item>
          <el-descriptions-item label="类目">{{ detail.categoryName ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="提供方主体编号">{{ detail.providerSubjectNo }}</el-descriptions-item>
          <el-descriptions-item label="上架时间">{{ detail.listedAt ?? '—' }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="isProviderMode" class="action-bar">
          <el-button v-if="!favorited" type="primary" class="favorite-btn" @click="onFavorite">
            {{ INTERACTION_ACTION_LABELS.FAVORITE }}
          </el-button>
          <el-button v-else class="unfavorite-btn" @click="onUnfavorite">
            {{ INTERACTION_ACTION_LABELS.UNFAVORITE }}
          </el-button>
          <el-button v-if="!subscribed" type="primary" plain class="subscribe-btn" @click="onSubscribe">
            {{ INTERACTION_ACTION_LABELS.SUBSCRIBE }}
          </el-button>
          <el-button v-else class="unsubscribe-btn" @click="onUnsubscribe">
            {{ INTERACTION_ACTION_LABELS.UNSUBSCRIBE }}
          </el-button>
        </div>

        <div class="change-logs">
          <h3 class="section-title">变更记录</h3>
          <p v-if="!subscribed" class="change-logs-tip">{{ CHANGE_LOGS_SUBSCRIPTION_REQUIRED_TIP }}</p>
          <template v-else>
            <el-table :data="changeLogs" v-loading="changeLogLoading" empty-text="暂无变更记录">
              <el-table-column prop="action" label="动作" width="120" />
              <el-table-column prop="summary" label="摘要（含从何值→到何值）" min-width="240" />
              <el-table-column prop="operatorSubjectNo" label="操作者主体编号" width="170" />
              <el-table-column prop="createdAt" label="时间" width="180" />
            </el-table>
            <el-pagination
              class="change-logs-pager"
              layout="prev, pager, next"
              :total="changeLogTotal"
              :page-size="10"
              :current-page="changeLogPageNum"
              @current-change="changeChangeLogPage"
            />
          </template>
        </div>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 4px;
  font-size: 18px;
}

.page-desc {
  margin: 0 0 16px;
  font-size: 13px;
  color: #6b7280;
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

.change-logs {
  margin-top: 8px;
}

.section-title {
  font-size: 14px;
  margin: 0 0 8px;
}

.change-logs-tip {
  font-size: 13px;
  color: #6b7280;
}

.change-logs-pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
