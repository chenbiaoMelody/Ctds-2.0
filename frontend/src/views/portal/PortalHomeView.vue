<script setup lang="ts">
/**
 * WBS-3.7.2 页面 1：数据产品门户首页（hifi §3.1；路由 /portal）：
 * - 检索区：关键词（R6）+ 类目树（R5）+ 分页（12/页）；无排序 / 无本地过滤（Q4-A）；
 * - 卡片网格：名称 / 定价标签 / 简介 / 形态 / 类目 / 提供方 / 上架时间 + 查看详情；
 * - 浏览只读：页面加载仅调用 R5/R6，不触发任何写端点（B1-3）；
 * - 空态不是报错；错误文案原样展示；401/403 走既有登录引导。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../api/client'
import {
  listCategories,
  searchProducts,
  type CategoryNode,
  type CatalogProduct,
} from '../../api/catalog'
import {
  PRICING_MODEL_LABELS,
  SEARCH_EMPTY_TIP,
  PORTAL_BROWSE_DESC,
  labelOf,
  pricingModelTagType,
} from '../../constants/catalog'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'

const router = useRouter()

const categories = ref<CategoryNode[]>([])
const items = ref<CatalogProduct[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(12)
const loading = ref(false)

const keyword = ref('')
const appliedKeyword = ref('')
const appliedCategoryCode = ref('')

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

async function loadProducts(): Promise<void> {
  loading.value = true
  try {
    const page = await searchProducts({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: appliedKeyword.value,
      categoryCode: appliedCategoryCode.value,
    })
    items.value = page.list
    total.value = page.total
  } catch (error) {
    items.value = []
    total.value = 0
    showError(error)
  } finally {
    loading.value = false
  }
}

function search(): void {
  pageNum.value = 1
  appliedKeyword.value = keyword.value.trim()
  void loadProducts()
}

function reset(): void {
  keyword.value = ''
  appliedKeyword.value = ''
  appliedCategoryCode.value = ''
  pageNum.value = 1
  void loadProducts()
}

function onCategoryClick(data: CategoryNode): void {
  appliedCategoryCode.value = data.categoryCode
  pageNum.value = 1
  void loadProducts()
}

function clearCategory(): void {
  appliedCategoryCode.value = ''
  pageNum.value = 1
  void loadProducts()
}

function changePage(page: number): void {
  pageNum.value = page
  void loadProducts()
}

function openDetail(item: CatalogProduct): void {
  void router.push(`/portal/products/${item.productId}`)
}

onMounted(() => {
  void loadCategories()
  void loadProducts()
})
</script>

<template>
  <div class="page">
    <h2 class="page-title">数据产品门户</h2>
    <p class="page-desc">{{ PORTAL_BROWSE_DESC }}</p>

    <el-card shadow="never">
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

          <div v-loading="loading" class="product-area">
            <div v-if="items.length" class="product-grid">
              <el-card v-for="item in items" :key="item.productId" shadow="hover" class="product-card">
                <div class="card-head">
                  <span class="product-name" :title="item.productName">{{ item.productName }}</span>
                  <el-tag :type="pricingModelTagType(item.pricingModel)" class="pricing-tag">
                    {{ labelOf(PRICING_MODEL_LABELS, item.pricingModel) }}
                  </el-tag>
                </div>
                <p class="product-intro">{{ item.intro ?? '—' }}</p>
                <dl class="card-meta">
                  <div class="meta-row">
                    <dt>形态</dt>
                    <dd>{{ item.productType }}</dd>
                  </div>
                  <div class="meta-row">
                    <dt>类目</dt>
                    <dd>{{ item.categoryName ?? '—' }}</dd>
                  </div>
                  <div class="meta-row">
                    <dt>提供方主体编号</dt>
                    <dd>{{ item.providerSubjectNo }}</dd>
                  </div>
                  <div class="meta-row">
                    <dt>上架时间</dt>
                    <dd>{{ item.listedAt ?? '—' }}</dd>
                  </div>
                </dl>
                <el-button type="primary" class="detail-btn" @click="openDetail(item)">查看详情</el-button>
              </el-card>
            </div>
            <el-empty v-else-if="!loading" class="empty-tip" :description="SEARCH_EMPTY_TIP" />
          </div>

          <el-pagination
            v-if="total > 0"
            class="pager"
            layout="prev, pager, next"
            :total="total"
            :page-size="pageSize"
            :current-page="pageNum"
            @current-change="changePage"
          />
        </div>
      </div>
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

.product-area {
  min-height: 120px;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.product-card {
  display: flex;
  flex-direction: column;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.product-name {
  font-size: 15px;
  font-weight: 600;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.pricing-tag {
  flex-shrink: 0;
}

.product-intro {
  margin: 8px 0;
  min-height: 40px;
  font-size: 13px;
  color: #4b5563;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-meta {
  margin: 0 0 12px;
  font-size: 12px;
  color: #6b7280;
}

.meta-row {
  display: flex;
  gap: 8px;
  line-height: 20px;
}

.meta-row dt {
  flex-shrink: 0;
  width: 96px;
}

.meta-row dd {
  margin: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.detail-btn {
  margin-top: auto;
  align-self: flex-start;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

@media (max-width: 1180px) {
  .product-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .portal-body {
    flex-direction: column;
  }

  .category-panel {
    width: auto;
    border-right: none;
    border-bottom: 1px solid #e5e7eb;
    padding: 0 0 12px;
  }

  .product-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
