<script setup lang="ts">
/**
 * WBS-3.3.6 页面 4：产品上架（hifi §6.5 / §6.8 弹窗 D/E/G / 测试锚点 T14/T15/T16/T20）：
 * - 我的产品列表（R11 全状态）+ 来源资源名映射（R1 命中显示资源名，超出页容量回退显示编号）；
 * - 封装弹窗（来源资源下拉含本人全部资源——已注销/已解散空间资源由服务端拒 1007C0016；
 *   免费档须显式选择且不提交数值）+ 状态机行级动作（上架/下架/重新上架/变更/注销）；
 * - 注销二次确认（明示不可恢复；取消 = 零请求）；在架注销由服务端 1007C0019 拒绝原样展示；
 * - 操作留痕区（R15 全值域：封装/变更/上下架/强制下架理由全文/拒绝/治理查看）；
 * - 运营档打开本页：列表被拒（1000C0005）原样展示 + 体验层提示切回普通主体档。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '../../api/client'
import { getActorMode } from '../../stores/demoIdentity'
import {
  listMyProducts,
  listMyDatasets,
  listCategories,
  createProduct,
  updateProduct,
  publishProduct,
  delistProduct,
  cancelProduct,
  listProductActionLogs,
  type ProviderProduct,
  type DatasetItem,
  type ProductActionLog,
  type CategoryNode,
} from '../../api/catalog'
import {
  PRODUCT_STATUS_LABELS,
  PRODUCT_ACTION_LABELS,
  PRICING_MODEL_LABELS,
  DATASET_TYPE_LABELS,
  DATASET_STATUS_LABELS,
  REPUBLISH_LABEL,
  PRODUCT_NAME_REQUIRED_TIP,
  PRODUCT_INTRO_REQUIRED_TIP,
  PRICING_MODEL_REQUIRED_TIP,
  SOURCE_DATASET_REQUIRED_TIP,
  PRICE_REQUIRED_TIP,
  OPERATOR_MODE_TIP,
  MY_PRODUCTS_EMPTY_TIP,
  PRODUCT_LOGS_EMPTY_TIP,
  productCancelConfirmTip,
  productStatusType,
  labelOf,
} from '../../constants/catalog'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'

const router = useRouter()

const loading = ref(false)
const products = ref<ProviderProduct[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const sourceDatasets = ref<DatasetItem[]>([])
const categoryOptions = ref<CategoryNode[]>([])
const loadFailed = ref(false)

const isOperatorMode = computed(() => getActorMode() === 'operator')

// ==== 封装弹窗（弹窗 D） ====
const createVisible = ref(false)
const submitting = ref(false)
const createForm = ref({
  datasetId: '' as string | number,
  productName: '',
  intro: '',
  productType: 'DATASET',
  pricingModel: '',
  priceAmount: '',
  categoryCode: '',
})

// ==== 变更弹窗（弹窗 E） ====
const updateVisible = ref(false)
const updating = ref(false)
const updateTarget = ref<ProviderProduct | null>(null)
const updateForm = ref({
  intro: '',
  productType: '',
  pricingModel: '',
  priceAmount: '',
  categoryCode: '',
})

// ==== 留痕区（R15） ====
const logTarget = ref<ProviderProduct | null>(null)
const logs = ref<ProductActionLog[]>([])
const logTotal = ref(0)
const logPageNum = ref(1)
const logLoading = ref(false)

function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error(error instanceof ApiError ? error.message : '请求失败，请稍后重试')
}

function sourceName(datasetId: number): string {
  const dataset = sourceDatasets.value.find((item) => item.id === datasetId)
  return dataset ? dataset.name : String(datasetId)
}

async function loadProducts(): Promise<void> {
  loading.value = true
  try {
    const page = await listMyProducts(pageNum.value, pageSize.value)
    products.value = page.list
    total.value = page.total
    loadFailed.value = false
    await loadSourceDatasets()
  } catch (error) {
    loadFailed.value = true
    showError(error)
  } finally {
    loading.value = false
  }
}

async function loadSourceDatasets(): Promise<void> {
  try {
    sourceDatasets.value = (await listMyDatasets(1, 100)).list
  } catch (error) {
    showError(error)
  }
}

async function openCreate(): Promise<void> {
  createForm.value = {
    datasetId: '',
    productName: '',
    intro: '',
    productType: 'DATASET',
    pricingModel: '',
    priceAmount: '',
    categoryCode: '',
  }
  createVisible.value = true
  if (sourceDatasets.value.length === 0) await loadSourceDatasets()
  if (categoryOptions.value.length === 0) {
    try {
      categoryOptions.value = await listCategories()
    } catch (error) {
      showError(error)
    }
  }
}

function flattenCategories(nodes: CategoryNode[]): Array<{ code: string; name: string }> {
  return nodes.flatMap((node) =>
    (node.children ?? []).map((child) => ({ code: child.categoryCode, name: child.categoryName })),
  )
}

async function submitCreate(): Promise<void> {
  const datasetId = createForm.value.datasetId
  if (datasetId === '' || datasetId === null) {
    ElMessage.error(SOURCE_DATASET_REQUIRED_TIP)
    return
  }
  const productName = createForm.value.productName.trim()
  if (!productName) {
    ElMessage.error(PRODUCT_NAME_REQUIRED_TIP)
    return
  }
  if (!createForm.value.intro.trim()) {
    ElMessage.error(PRODUCT_INTRO_REQUIRED_TIP)
    return
  }
  const pricingModel = createForm.value.pricingModel
  if (!pricingModel) {
    ElMessage.error(PRICING_MODEL_REQUIRED_TIP)
    return
  }
  // 付费档数值前置校验（零请求）；免费档不填数值
  if (pricingModel !== 'FREE' && !createForm.value.priceAmount.trim()) {
    ElMessage.error(PRICE_REQUIRED_TIP)
    return
  }
  submitting.value = true
  try {
    const payload: {
      datasetId: number
      productName: string
      intro: string
      productType: string
      pricingModel: string
      priceAmount?: string
      categoryCode?: string
    } = {
      datasetId: Number(datasetId),
      productName,
      intro: createForm.value.intro.trim(),
      productType: createForm.value.productType,
      pricingModel,
    }
    // 免费档不提交数值；付费档随档提交数值
    if (pricingModel !== 'FREE') payload.priceAmount = createForm.value.priceAmount.trim()
    if (createForm.value.categoryCode) payload.categoryCode = createForm.value.categoryCode
    await createProduct(payload)
    ElMessage.success(`封装成功，产品处于${PRODUCT_STATUS_LABELS.DRAFT}初始态`)
    createVisible.value = false
    pageNum.value = 1
    await loadProducts()
  } catch (error) {
    showError(error)
  } finally {
    submitting.value = false
  }
}

async function openUpdate(row: ProviderProduct): Promise<void> {
  updateTarget.value = row
  updateForm.value = {
    intro: row.intro ?? '',
    productType: productTypeKey(row.productType),
    pricingModel: pricingModelKey(row.pricingModel),
    priceAmount: row.priceAmount ?? '',
    categoryCode: row.categoryCode ?? '',
  }
  updateVisible.value = true
  if (categoryOptions.value.length === 0) {
    try {
      categoryOptions.value = await listCategories()
    } catch (error) {
      showError(error)
    }
  }
}

/** 产品面中文显示名 → 枚举键（W9 请求体提交枚举；产品面出参为中文——DB-36 双轨）。 */
function productTypeKey(displayName: string): string {
  return Object.entries(DATASET_TYPE_LABELS).find(([, label]) => label === displayName)?.[0] ?? displayName
}

/** 定价面中文显示名 → 枚举键（同上）。 */
function pricingModelKey(displayName: string): string {
  return Object.entries(PRICING_MODEL_LABELS).find(([, label]) => label === displayName)?.[0] ?? displayName
}

async function submitUpdate(): Promise<void> {
  const target = updateTarget.value
  if (!target) return
  // 请求体只含可变白名单字段（W9：名称不可变）；免费档切档清空数值（不提交 priceAmount）
  const payload: { intro?: string; productType?: string; pricingModel?: string; priceAmount?: string; categoryCode?: string } = {}
  if (updateForm.value.intro.trim() !== (target.intro ?? '')) payload.intro = updateForm.value.intro.trim()
  if (updateForm.value.productType && updateForm.value.productType !== productTypeKey(target.productType)) {
    payload.productType = updateForm.value.productType
  }
  if (updateForm.value.pricingModel !== pricingModelKey(target.pricingModel)) payload.pricingModel = updateForm.value.pricingModel
  if (createFormCategoryChanged(target.categoryCode)) payload.categoryCode = updateForm.value.categoryCode
  if (updateForm.value.pricingModel === 'FREE') {
    // 免费档：不携带数值（服务端清空 price_amount）
  } else if (updateForm.value.priceAmount.trim() !== (target.priceAmount ?? '')) {
    payload.priceAmount = updateForm.value.priceAmount.trim()
  }
  if (Object.keys(payload).length === 0) {
    ElMessage.error('请至少修改一项可变字段')
    return
  }
  updating.value = true
  try {
    await updateProduct(target.productId, payload)
    ElMessage.success('变更成功')
    updateVisible.value = false
    await loadProducts()
  } catch (error) {
    showError(error)
  } finally {
    updating.value = false
  }
}

async function onPublish(row: ProviderProduct): Promise<void> {
  try {
    await publishProduct(row.productId)
    ElMessage.success('已进入统一目录')
    await loadProducts()
  } catch (error) {
    showError(error)
  }
}

async function onDelist(row: ProviderProduct): Promise<void> {
  try {
    await delistProduct(row.productId)
    ElMessage.success('已退出统一目录')
    await loadProducts()
  } catch (error) {
    showError(error)
  }
}

async function onCancel(row: ProviderProduct): Promise<void> {
  try {
    await ElMessageBox.confirm(productCancelConfirmTip(row.productName), '注销产品', {
      confirmButtonText: '确认注销',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await cancelProduct(row.productId)
    ElMessage.success('注销成功')
    await loadProducts()
  } catch (error) {
    showError(error)
  }
}

async function openLogs(row: ProviderProduct): Promise<void> {
  logTarget.value = row
  logPageNum.value = 1
  await loadLogs()
}

async function loadLogs(): Promise<void> {
  const target = logTarget.value
  if (!target) return
  logLoading.value = true
  try {
    const page = await listProductActionLogs(target.productId, logPageNum.value, 10)
    logs.value = page.list
    logTotal.value = page.total
  } catch (error) {
    showError(error)
  } finally {
    logLoading.value = false
  }
}

function changeLogPage(page: number): void {
  logPageNum.value = page
  void loadLogs()
}

function statusText(status: string): string {
  return labelOf(PRODUCT_STATUS_LABELS, status)
}

function isDraft(status: string): boolean {
  return statusText(status) === PRODUCT_STATUS_LABELS.DRAFT
}

function isListed(status: string): boolean {
  return statusText(status) === PRODUCT_STATUS_LABELS.LISTED
}

function isDelisted(status: string): boolean {
  return statusText(status) === PRODUCT_STATUS_LABELS.DELISTED
}

function createFormCategoryChanged(targetCode: string | null): boolean {
  return (updateForm.value.categoryCode ?? '') !== (targetCode ?? '')
}

function datasetStatusLabel(datasetId: number): string {
  const dataset = sourceDatasets.value.find((item) => item.id === datasetId)
  return dataset ? labelOf(DATASET_STATUS_LABELS, dataset.status) : '—'
}

onMounted(() => {
  void loadProducts()
})
</script>

<template>
  <div class="page">
    <h2 class="page-title">产品上架</h2>
    <p class="page-desc">从自有资源封装数据产品并管理上下架（上架须定价要素齐备；进入目录检索须先上架）。</p>

    <el-alert
      v-if="isOperatorMode && loadFailed"
      class="operator-tip"
      :title="OPERATOR_MODE_TIP"
      type="info"
      show-icon
      :closable="false"
    />

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" class="create-open" @click="openCreate">封装产品</el-button>
        <el-button class="refresh-btn" @click="loadProducts">刷新</el-button>
      </div>

      <el-table :data="products" v-loading="loading" :empty-text="MY_PRODUCTS_EMPTY_TIP">
        <el-table-column prop="productName" label="产品名称" min-width="170" />
        <el-table-column label="来源资源" min-width="150">
          <template #default="scope">{{ sourceName(scope.row.datasetId) }}</template>
        </el-table-column>
        <el-table-column label="形态" width="90">
          <template #default="scope">{{ scope.row.productType }}</template>
        </el-table-column>
        <el-table-column label="定价" width="140">
          <template #default="scope">
            {{ scope.row.pricingModel }}{{ scope.row.priceAmount ? `（${scope.row.priceAmount}）` : '' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="scope">
            <el-tag :type="productStatusType(scope.row.status)">{{ statusText(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" label="类目" width="110" />
        <el-table-column label="上架时间" width="180">
          <template #default="scope">{{ scope.row.listedAt ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="scope">
            <el-button
              v-if="isDraft(scope.row.status)"
              type="primary"
              link
              class="publish-btn"
              @click="onPublish(scope.row)"
            >{{ PRODUCT_ACTION_LABELS.PUBLISH }}</el-button>
            <el-button
              v-if="isListed(scope.row.status)"
              type="warning"
              link
              class="delist-btn"
              @click="onDelist(scope.row)"
            >{{ PRODUCT_ACTION_LABELS.DELIST }}</el-button>
            <el-button
              v-if="isDelisted(scope.row.status)"
              type="primary"
              link
              class="republish-btn"
              @click="onPublish(scope.row)"
            >{{ REPUBLISH_LABEL }}</el-button>
            <el-button
              v-if="scope.row.status !== PRODUCT_STATUS_LABELS.CANCELLED"
              type="primary"
              link
              class="update-btn"
              @click="openUpdate(scope.row)"
            >变更</el-button>
            <el-button
              v-if="scope.row.status !== PRODUCT_STATUS_LABELS.CANCELLED"
              type="danger"
              link
              class="cancel-btn"
              @click="onCancel(scope.row)"
            >注销</el-button>
            <el-button type="info" link class="log-btn" @click="openLogs(scope.row)">留痕</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="products-pager"
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="pageNum"
        @current-change="(page: number) => { pageNum = page; void loadProducts() }"
      />
    </el-card>

    <el-card v-if="logTarget" shadow="never" class="logs-card">
      <h3 class="section-title">操作留痕 — {{ logTarget.productName }}</h3>
      <el-table :data="logs" v-loading="logLoading" :empty-text="PRODUCT_LOGS_EMPTY_TIP">
        <el-table-column label="动作" width="120">
          <template #default="scope">{{ labelOf(PRODUCT_ACTION_LABELS, scope.row.action) }}</template>
        </el-table-column>
        <el-table-column prop="summary" label="摘要（含理由全文与从何值→到何值）" min-width="260" />
        <el-table-column prop="operatorSubjectNo" label="操作者" width="170" />
        <el-table-column prop="createdAt" label="时间" width="180" />
      </el-table>
      <el-pagination
        class="product-logs-pager"
        layout="prev, pager, next"
        :total="logTotal"
        :page-size="10"
        :current-page="logPageNum"
        @current-change="changeLogPage"
      />
    </el-card>

    <el-dialog v-model="createVisible" title="封装产品" width="620px" class="create-dialog">
      <el-form label-width="120px">
        <el-form-item label="来源资源" required>
          <el-select v-model="createForm.datasetId" class="create-dataset" placeholder="选择本人资源（状态由服务端判定为准）">
            <el-option
              v-for="dataset in sourceDatasets"
              :key="dataset.id"
              :label="`${dataset.name}（${datasetStatusLabel(dataset.id)}）`"
              :value="dataset.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="产品名称" required>
          <el-input v-model="createForm.productName" class="create-name" maxlength="128" placeholder="产品名称（≤128，同一提供方内唯一）" />
        </el-form-item>
        <el-form-item label="简介" required>
          <el-input v-model="createForm.intro" class="create-intro" type="textarea" maxlength="512" :rows="3" />
        </el-form-item>
        <el-form-item label="产品形态" required>
          <el-select v-model="createForm.productType" class="create-type">
            <el-option :label="DATASET_TYPE_LABELS.API" value="API" />
            <el-option :label="DATASET_TYPE_LABELS.DATASET" value="DATASET" />
            <el-option :label="DATASET_TYPE_LABELS.REPORT" value="REPORT" />
            <el-option :label="DATASET_TYPE_LABELS.MODEL" value="MODEL" />
          </el-select>
        </el-form-item>
        <el-form-item label="定价档" required>
          <el-select v-model="createForm.pricingModel" class="create-pricing" :placeholder="`${PRICING_MODEL_LABELS.FREE}也须显式选择`">
            <el-option :label="PRICING_MODEL_LABELS.FREE" value="FREE" />
            <el-option :label="PRICING_MODEL_LABELS.PER_CALL" value="PER_CALL" />
            <el-option :label="PRICING_MODEL_LABELS.MONTHLY" value="MONTHLY" />
            <el-option :label="PRICING_MODEL_LABELS.REVENUE_SHARE" value="REVENUE_SHARE" />
          </el-select>
        </el-form-item>
        <el-form-item label="价格数值">
          <el-input
            v-model="createForm.priceAmount"
            class="create-price"
            :disabled="createForm.pricingModel === 'FREE'"
            :placeholder="`付费档必填（如 3.00；${PRICING_MODEL_LABELS.FREE}档不填）`"
          />
        </el-form-item>
        <el-form-item label="类目">
          <el-select v-model="createForm.categoryCode" class="create-category" clearable placeholder="可选；缺省继承资源申报类目">
            <el-option v-for="cat in flattenCategories(categoryOptions)" :key="cat.code" :label="cat.name" :value="cat.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" class="create-submit" :loading="submitting" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="updateVisible" title="变更产品" width="560px" class="update-dialog">
      <el-form label-width="120px">
        <el-form-item label="产品名称">
          <el-input :model-value="updateTarget?.productName" disabled />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="updateForm.intro" class="update-intro" type="textarea" maxlength="512" :rows="3" />
        </el-form-item>
        <el-form-item label="产品形态">
          <el-select v-model="updateForm.productType" class="update-type">
            <el-option :label="DATASET_TYPE_LABELS.API" value="API" />
            <el-option :label="DATASET_TYPE_LABELS.DATASET" value="DATASET" />
            <el-option :label="DATASET_TYPE_LABELS.REPORT" value="REPORT" />
            <el-option :label="DATASET_TYPE_LABELS.MODEL" value="MODEL" />
          </el-select>
        </el-form-item>
        <el-form-item label="定价档">
          <el-select v-model="updateForm.pricingModel" class="update-pricing">
            <el-option :label="PRICING_MODEL_LABELS.FREE" value="FREE" />
            <el-option :label="PRICING_MODEL_LABELS.PER_CALL" value="PER_CALL" />
            <el-option :label="PRICING_MODEL_LABELS.MONTHLY" value="MONTHLY" />
            <el-option :label="PRICING_MODEL_LABELS.REVENUE_SHARE" value="REVENUE_SHARE" />
          </el-select>
        </el-form-item>
        <el-form-item label="价格数值">
          <el-input v-model="updateForm.priceAmount" class="update-price" :disabled="updateForm.pricingModel === 'FREE'" />
        </el-form-item>
        <el-form-item label="类目">
          <el-select v-model="updateForm.categoryCode" class="update-category" clearable placeholder="可选；缺省继承资源申报类目">
            <el-option v-for="cat in flattenCategories(categoryOptions)" :key="cat.code" :label="cat.name" :value="cat.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="updateVisible = false">取消</el-button>
        <el-button type="primary" class="update-submit" :loading="updating" @click="submitUpdate">提交</el-button>
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

.operator-tip {
  margin-bottom: 12px;
}

.toolbar {
  margin-bottom: 12px;
}

.logs-card {
  margin-top: 16px;
}

.section-title {
  font-size: 14px;
  margin: 0 0 8px;
}

.products-pager,
.product-logs-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.create-dataset,
.create-pricing,
.create-category {
  width: 100%;
}

.update-pricing {
  width: 100%;
}
</style>
