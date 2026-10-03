<script setup lang="ts">
/**
 * WBS-3.3.6 页面 1：数据目录·检索门户（hifi §6.2 / 测试锚点 T4/T5/T8/T9；替换 2.4.9 占位页）：
 * - 页签 1 目录检索：类目树（父类目含子树由服务端展开）+ 关键词 + 结果分页（仅目录元数据）；
 * - 页签 2/3 我的收藏 / 我的订阅：条目保留并标记产品当前状态（历史不删除）；
 * - 页签 4 我的互动留痕：收藏/订阅/取消/退订与拒绝记录（恒仅本人）；
 * - 未入驻主体检索被拒：统一业务文案原样展示（防枚举）；空态不是报错。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { TabsPaneContext } from 'element-plus'
import { ApiError } from '../../api/client'
import {
  listCategories,
  searchProducts,
  listFavorites,
  unfavoriteProduct,
  listSubscriptions,
  unsubscribeProduct,
  listInteractionLogs,
  type CategoryNode,
  type CatalogProduct,
  type FavoriteItem,
  type SubscriptionItem,
  type InteractionLog,
} from '../../api/catalog'
import {
  PRODUCT_STATUS_LABELS,
  productStatusType,
  INTERACTION_ACTION_LABELS,
  INTERACTION_OUTCOME_LABELS,
  INTERACTION_OUTCOME_TYPES,
  SEARCH_EMPTY_TIP,
  FAVORITES_EMPTY_TIP,
  SUBSCRIPTIONS_EMPTY_TIP,
  INTERACTION_LOGS_EMPTY_TIP,
  labelOf,
} from '../../constants/catalog'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'

const router = useRouter()

const activeTab = ref('search')
const loadedTabs = ref<Record<string, boolean>>({ search: true })

const categories = ref<CategoryNode[]>([])
const items = ref<CatalogProduct[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const searchLoading = ref(false)

const keyword = ref('')
const appliedKeyword = ref('')
const appliedCategoryCode = ref('')

const favoriteItems = ref<FavoriteItem[]>([])
const favoriteTotal = ref(0)
const favoritePageNum = ref(1)
const favoriteLoading = ref(false)

const subscriptionItems = ref<SubscriptionItem[]>([])
const subscriptionTotal = ref(0)
const subscriptionPageNum = ref(1)
const subscriptionLoading = ref(false)

const interactionItems = ref<InteractionLog[]>([])
const interactionTotal = ref(0)
const interactionPageNum = ref(1)
const interactionLoading = ref(false)

function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error(error instanceof ApiError ? error.message : '请求失败，请稍后重试')
}

async function loadCategories(): Promise<void> {
  try {
    categories.value = await listCategories()
  } catch (error) {
    showError(error)
  }
}

async function loadSearch(): Promise<void> {
  searchLoading.value = true
  try {
    const page = await searchProducts(pageNum.value, pageSize.value, appliedKeyword.value, appliedCategoryCode.value)
    items.value = page.list
    total.value = page.total
  } catch (error) {
    showError(error)
  } finally {
    searchLoading.value = false
  }
}

function onCategoryClick(data: CategoryNode): void {
  appliedCategoryCode.value = data.categoryCode
  pageNum.value = 1
  void loadSearch()
}

function clearCategory(): void {
  appliedCategoryCode.value = ''
  pageNum.value = 1
  void loadSearch()
}

function search(): void {
  pageNum.value = 1
  appliedKeyword.value = keyword.value.trim()
  void loadSearch()
}

function reset(): void {
  keyword.value = ''
  appliedKeyword.value = ''
  appliedCategoryCode.value = ''
  pageNum.value = 1
  void loadSearch()
}

function changePage(page: number): void {
  pageNum.value = page
  void loadSearch()
}

function openDetail(row: CatalogProduct): void {
  void router.push(`/catalog/products/${row.productId}`)
}

async function loadFavorites(): Promise<void> {
  favoriteLoading.value = true
  try {
    const page = await listFavorites(favoritePageNum.value, 10)
    favoriteItems.value = page.list
    favoriteTotal.value = page.total
  } catch (error) {
    showError(error)
  } finally {
    favoriteLoading.value = false
  }
}

async function loadSubscriptions(): Promise<void> {
  subscriptionLoading.value = true
  try {
    const page = await listSubscriptions(subscriptionPageNum.value, 10)
    subscriptionItems.value = page.list
    subscriptionTotal.value = page.total
  } catch (error) {
    showError(error)
  } finally {
    subscriptionLoading.value = false
  }
}

async function loadInteractions(): Promise<void> {
  interactionLoading.value = true
  try {
    const page = await listInteractionLogs(interactionPageNum.value, 10)
    interactionItems.value = page.list
    interactionTotal.value = page.total
  } catch (error) {
    showError(error)
  } finally {
    interactionLoading.value = false
  }
}

function changeFavoritePage(page: number): void {
  favoritePageNum.value = page
  void loadFavorites()
}

function changeSubscriptionPage(page: number): void {
  subscriptionPageNum.value = page
  void loadSubscriptions()
}

function changeInteractionPage(page: number): void {
  interactionPageNum.value = page
  void loadInteractions()
}

async function onUnfavorite(row: FavoriteItem): Promise<void> {
  try {
    await unfavoriteProduct(row.productId)
    ElMessage.success(`${INTERACTION_ACTION_LABELS.UNFAVORITE}成功`)
    await loadFavorites()
  } catch (error) {
    showError(error)
  }
}

async function onUnsubscribe(row: SubscriptionItem): Promise<void> {
  try {
    await unsubscribeProduct(row.productId)
    ElMessage.success(`${INTERACTION_ACTION_LABELS.UNSUBSCRIBE}成功`)
    await loadSubscriptions()
  } catch (error) {
    showError(error)
  }
}

function onTabChange(pane: string | TabsPaneContext): void {
  const name = typeof pane === 'string' ? pane : String(pane.paneName ?? pane.props?.name ?? '')
  if (loadedTabs.value[name]) return
  loadedTabs.value[name] = true
  if (name === 'favorites') void loadFavorites()
  if (name === 'subscriptions') void loadSubscriptions()
  if (name === 'interactions') void loadInteractions()
}

function statusType(value: string): string {
  return productStatusType(value)
}

onMounted(() => {
  void loadCategories()
  void loadSearch()
})
</script>

<template>
  <div class="page">
    <h2 class="page-title">数据目录</h2>
    <p class="page-desc">检索在架数据产品（仅目录元数据；收藏与订阅在下方页签管理）。</p>

    <el-card shadow="never">
      <el-tabs v-model="activeTab" @tab-click="onTabChange">
        <el-tab-pane label="目录检索" name="search">
          <div class="portal-body">
            <div class="category-panel">
              <div class="category-head">
                <span class="category-title">类目</span>
                <el-button link class="category-all-btn" @click="clearCategory">全部</el-button>
              </div>
              <el-tree
                :data="categories"
                node-key="categoryCode"
                default-expand-all
                :props="{ label: 'categoryName', children: 'children' }"
                highlight-current
                @node-click="onCategoryClick"
              />
            </div>
            <div class="result-panel">
              <div class="searchbar">
                <el-input
                  v-model="keyword"
                  class="keyword-input"
                  maxlength="64"
                  placeholder="产品名称 / 简介关键字"
                  clearable
                />
                <el-button type="primary" class="search-btn" @click="search">查询</el-button>
                <el-button class="reset-btn" @click="reset">重置</el-button>
              </div>

              <el-table :data="items" v-loading="searchLoading" :empty-text="SEARCH_EMPTY_TIP">
                <el-table-column prop="productName" label="产品名称" min-width="180" />
                <el-table-column prop="intro" label="简介" min-width="200" show-overflow-tooltip />
                <el-table-column label="形态" width="90">
                  <template #default="scope">{{ scope.row.productType }}</template>
                </el-table-column>
                <el-table-column label="定价模型" width="110">
                  <template #default="scope">{{ scope.row.pricingModel }}</template>
                </el-table-column>
                <el-table-column prop="categoryName" label="类目" width="120" />
                <el-table-column prop="providerSubjectNo" label="提供方主体编号" width="170" />
                <el-table-column prop="listedAt" label="上架时间" width="180" />
                <el-table-column label="操作" width="100">
                  <template #default="scope">
                    <el-button type="primary" link class="detail-btn" @click="openDetail(scope.row)">查看详情</el-button>
                  </template>
                </el-table-column>
              </el-table>

              <el-pagination
                class="pager"
                layout="prev, pager, next"
                :total="total"
                :page-size="pageSize"
                :current-page="pageNum"
                @current-change="changePage"
              />
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="我的收藏" name="favorites">
          <el-table :data="favoriteItems" v-loading="favoriteLoading" :empty-text="FAVORITES_EMPTY_TIP">
            <el-table-column prop="productName" label="产品名称" min-width="180" />
            <el-table-column label="形态" width="90">
              <template #default="scope">{{ scope.row.productType }}</template>
            </el-table-column>
            <el-table-column label="定价" width="110">
              <template #default="scope">{{ scope.row.pricingModel }}</template>
            </el-table-column>
            <el-table-column label="产品当前状态" width="130">
              <template #default="scope">
                <el-tag :type="statusType(scope.row.productStatus)">
                  {{ labelOf(PRODUCT_STATUS_LABELS, scope.row.productStatus) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="favoritedAt" label="收藏时间" width="180" />
            <el-table-column label="操作" width="110">
              <template #default="scope">
                <el-button type="warning" link class="unfavorite-btn" @click="onUnfavorite(scope.row)">
                  {{ INTERACTION_ACTION_LABELS.UNFAVORITE }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="favorites-pager pager"
            layout="prev, pager, next"
            :total="favoriteTotal"
            :page-size="10"
            :current-page="favoritePageNum"
            @current-change="changeFavoritePage"
          />
        </el-tab-pane>

        <el-tab-pane label="我的订阅" name="subscriptions">
          <el-table :data="subscriptionItems" v-loading="subscriptionLoading" :empty-text="SUBSCRIPTIONS_EMPTY_TIP">
            <el-table-column prop="productName" label="产品名称" min-width="180" />
            <el-table-column label="形态" width="90">
              <template #default="scope">{{ scope.row.productType }}</template>
            </el-table-column>
            <el-table-column label="定价" width="110">
              <template #default="scope">{{ scope.row.pricingModel }}</template>
            </el-table-column>
            <el-table-column label="产品当前状态" width="130">
              <template #default="scope">
                <el-tag :type="statusType(scope.row.productStatus)">
                  {{ labelOf(PRODUCT_STATUS_LABELS, scope.row.productStatus) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="subscribedAt" label="订阅时间" width="180" />
            <el-table-column label="操作" width="110">
              <template #default="scope">
                <el-button type="warning" link class="unsubscribe-btn" @click="onUnsubscribe(scope.row)">
                  {{ INTERACTION_ACTION_LABELS.UNSUBSCRIBE }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="subscriptions-pager pager"
            layout="prev, pager, next"
            :total="subscriptionTotal"
            :page-size="10"
            :current-page="subscriptionPageNum"
            @current-change="changeSubscriptionPage"
          />
        </el-tab-pane>

        <el-tab-pane label="我的互动留痕" name="interactions">
          <el-table :data="interactionItems" v-loading="interactionLoading" :empty-text="INTERACTION_LOGS_EMPTY_TIP">
            <el-table-column label="动作" width="110">
              <template #default="scope">{{ labelOf(INTERACTION_ACTION_LABELS, scope.row.action) }}</template>
            </el-table-column>
            <el-table-column label="结果" width="90">
              <template #default="scope">
                <el-tag :type="INTERACTION_OUTCOME_TYPES[scope.row.outcome] ?? 'info'">
                  {{ labelOf(INTERACTION_OUTCOME_LABELS, scope.row.outcome) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="productId" label="产品编号" width="100" />
            <el-table-column prop="createdAt" label="时间" width="180" />
            <el-table-column label="拒绝原因" width="120">
              <template #default="scope">{{ scope.row.denyReason ?? '—' }}</template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="interactions-pager pager"
            layout="prev, pager, next"
            :total="interactionTotal"
            :page-size="10"
            :current-page="interactionPageNum"
            @current-change="changeInteractionPage"
          />
        </el-tab-pane>
      </el-tabs>
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

.portal-body {
  display: flex;
  gap: 16px;
}

.category-panel {
  width: 220px;
  flex-shrink: 0;
  border-right: 1px solid #e5e7eb;
  padding-right: 12px;
}

.category-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.category-title {
  font-size: 13px;
  color: #6b7280;
}

.result-panel {
  flex: 1;
  min-width: 0;
}

.searchbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.keyword-input {
  width: 260px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
