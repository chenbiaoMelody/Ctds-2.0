<script setup lang="ts">
/**
 * WBS-3.3.6 页面 5：目录治理（hifi §6.6 / §6.8 弹窗 F；承载剧本 C-3.2 S3-3 / C-3.3 S3-3 判定面）：
 * - 按产品编号 / 资源编号直查任意状态对象的治理信息（治理例外显式语义，不防枚举；
 *   对象缺失 = 404 语义业务码原样展示）；
 * - 在架产品可强制下架（理由必填 1~256，留空 = 前置拦截零请求）；每次查看写 GOVERNANCE_VIEW 留痕
 *   （提供方在资源登记页 / 产品上架页的留痕区核对——说明文案经常量承载）。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../api/client'
import {
  getProductGovernance,
  getDatasetGovernance,
  forceDelistProduct,
  type ProviderProduct,
  type DatasetItem,
} from '../../api/catalog'
import {
  DATASET_TYPE_LABELS,
  DATASET_STATUS_LABELS,
  DATASET_STATUS_TYPES,
  FORCE_REASON_REQUIRED_TIP,
  QUERY_ID_REQUIRED_TIP,
  GOVERNANCE_VIEW_TIP,
  PRODUCT_ACTION_LABELS,
  productStatusType,
  labelOf,
} from '../../constants/catalog'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'

const router = useRouter()

const productIdInput = ref('')
const datasetIdInput = ref('')
const product = ref<ProviderProduct | null>(null)
const dataset = ref<DatasetItem | null>(null)
const queryLoading = ref(false)

const forceDelistVisible = ref(false)
const forceDelisting = ref(false)
const forceReason = ref('')

function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error(error instanceof ApiError ? error.message : '请求失败，请稍后重试')
}

async function queryProduct(): Promise<void> {
  const id = productIdInput.value.trim()
  if (!id) {
    ElMessage.error(QUERY_ID_REQUIRED_TIP)
    return
  }
  if (!/^\d+$/.test(id)) {
    ElMessage.error(QUERY_ID_REQUIRED_TIP)
    return
  }
  queryLoading.value = true
  dataset.value = null
  try {
    product.value = await getProductGovernance(Number(id))
  } catch (error) {
    product.value = null
    showError(error)
  } finally {
    queryLoading.value = false
  }
}

async function queryDataset(): Promise<void> {
  const id = datasetIdInput.value.trim()
  if (!id) {
    ElMessage.error(QUERY_ID_REQUIRED_TIP)
    return
  }
  if (!/^\d+$/.test(id)) {
    ElMessage.error(QUERY_ID_REQUIRED_TIP)
    return
  }
  queryLoading.value = true
  product.value = null
  try {
    dataset.value = await getDatasetGovernance(Number(id))
  } catch (error) {
    dataset.value = null
    showError(error)
  } finally {
    queryLoading.value = false
  }
}

function openForceDelist(): void {
  forceReason.value = ''
  forceDelistVisible.value = true
}

async function submitForceDelist(): Promise<void> {
  const target = product.value
  if (!target) return
  const reason = forceReason.value.trim()
  if (!reason) {
    ElMessage.error(FORCE_REASON_REQUIRED_TIP)
    return
  }
  forceDelisting.value = true
  try {
    await forceDelistProduct(target.productId, reason)
    ElMessage.success(`${PRODUCT_ACTION_LABELS.FORCE_DELIST}成功（留痕含理由与操作者）`)
    forceDelistVisible.value = false
    // 以响应为准：重新直查治理视图
    await queryProduct()
  } catch (error) {
    showError(error)
  } finally {
    forceDelisting.value = false
  }
}

function datasetStatusType(value: string): string {
  return DATASET_STATUS_TYPES[value] ?? 'info'
}

</script>

<template>
  <div class="page">
    <h2 class="page-title">目录治理</h2>
    <p class="page-desc">{{ GOVERNANCE_VIEW_TIP }}。</p>

    <el-card shadow="never" class="query-card">
      <div class="query-row">
        <el-input v-model="productIdInput" class="product-id-input" placeholder="产品编号" />
        <el-button type="primary" class="query-product-btn" :loading="queryLoading" @click="queryProduct">查看产品治理信息</el-button>
      </div>
      <div class="query-row">
        <el-input v-model="datasetIdInput" class="dataset-id-input" placeholder="资源编号（资源 id）" />
        <el-button type="primary" class="query-dataset-btn" :loading="queryLoading" @click="queryDataset">查看资源治理信息</el-button>
      </div>
    </el-card>

    <el-card v-if="product" shadow="never" class="result-card">
      <div class="meta-head">
        <span class="object-name">{{ product.productName }}</span>
        <el-tag :type="productStatusType(product.status)">{{ product.status }}</el-tag>
        <el-button
          type="danger"
          class="force-delist-btn"
          @click="openForceDelist"
        >{{ PRODUCT_ACTION_LABELS.FORCE_DELIST }}</el-button>
      </div>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="产品编号">{{ product.productId }}</el-descriptions-item>
        <el-descriptions-item label="简介">{{ product.intro ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="形态">{{ product.productType }}</el-descriptions-item>
        <el-descriptions-item label="定价">{{ product.pricingModel }}{{ product.priceAmount ? `（${product.priceAmount}）` : '' }}</el-descriptions-item>
        <el-descriptions-item label="类目">{{ product.categoryName ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="提供方主体编号">{{ product.providerSubjectNo }}</el-descriptions-item>
        <el-descriptions-item label="来源资源编号">{{ product.datasetId }}</el-descriptions-item>
        <el-descriptions-item label="上架时间">{{ product.listedAt ?? '—' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card v-if="dataset" shadow="never" class="result-card">
      <div class="meta-head">
        <span class="object-name">{{ dataset.name }}</span>
        <el-tag :type="datasetStatusType(dataset.status)">{{ labelOf(DATASET_STATUS_LABELS, dataset.status) }}</el-tag>
      </div>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="资源编号">{{ dataset.id }}</el-descriptions-item>
        <el-descriptions-item label="数据标识">{{ dataset.dataNo }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ labelOf(DATASET_TYPE_LABELS, dataset.type) }}</el-descriptions-item>
        <el-descriptions-item label="所属空间">{{ dataset.spaceId }}</el-descriptions-item>
        <el-descriptions-item label="简介">{{ dataset.intro }}</el-descriptions-item>
        <el-descriptions-item label="语义标签">{{ dataset.tags.join('、') }}</el-descriptions-item>
        <el-descriptions-item label="分类分级申报">{{ dataset.declareCategory }} / {{ dataset.declareLevel }}</el-descriptions-item>
        <el-descriptions-item label="登记时间">{{ dataset.createdAt }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-dialog v-model="forceDelistVisible" :title="PRODUCT_ACTION_LABELS.FORCE_DELIST" width="520px" class="force-delist-dialog">
      <el-form label-width="100px">
        <el-form-item label="下架理由" required>
          <el-input v-model="forceReason" class="force-reason" type="textarea" maxlength="256" :rows="3" placeholder="必填（1~256 字），留痕含理由全文与操作者" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="forceDelistVisible = false">取消</el-button>
        <el-button type="danger" class="force-delist-submit" :loading="forceDelisting" @click="submitForceDelist">确认{{ PRODUCT_ACTION_LABELS.FORCE_DELIST }}</el-button>
      </template>
    </el-dialog>
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

.query-card {
  margin-bottom: 16px;
}

.query-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.query-row:last-child {
  margin-bottom: 0;
}

.product-id-input,
.dataset-id-input {
  width: 280px;
}

.result-card {
  margin-bottom: 16px;
}

.meta-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.object-name {
  font-size: 16px;
  font-weight: 600;
}
</style>
