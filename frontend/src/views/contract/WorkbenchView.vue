<template>
  <div class="workbench-page">
    <div class="operation-bar">
      <el-button type="primary" @click="openInitiate">发起合约</el-button>
    </div>

    <!-- 我的合约（R6；恒仅本主体参与） -->
    <div class="filter-bar">
      <el-select v-model="statusFilter" clearable placeholder="状态" class="type-select">
        <el-option
          v-for="(label, code) in CONTRACT_STATUS_LABELS"
          :key="code"
          :label="label"
          :value="code"
        />
      </el-select>
      <el-select v-model="roleFilter" clearable placeholder="我的角色" class="type-select">
        <el-option v-for="(label, code) in MY_ROLE_LABELS" :key="code" :label="label" :value="code" />
      </el-select>
      <el-button type="primary" @click="loadMine">查询</el-button>
      <el-button @click="resetFilter">重置</el-button>
    </div>
    <el-table v-loading="loading" :data="list" :empty-text="'暂无合约'">
      <el-table-column prop="contractNo" label="合约编号" width="140" />
      <el-table-column prop="productName" label="产品名称" min-width="180" />
      <el-table-column prop="counterpartySubjectNo" label="对方主体编号" min-width="170" />
      <el-table-column label="我的角色" width="100">
        <template #default="scope">{{ MY_ROLE_LABELS[scope.row.myRole] ?? scope.row.myRole }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="scope">
          <el-tag :type="CONTRACT_STATUS_TAG_TYPES[scope.row.status] ?? 'info'">
            {{ CONTRACT_STATUS_LABELS[scope.row.status] ?? scope.row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="当前条款版本" width="110">
        <template #default="scope">V{{ scope.row.currentClauseVersion }}</template>
      </el-table-column>
      <el-table-column prop="effectiveAt" label="生效时间" width="180">
        <template #default="scope">{{ scope.row.effectiveAt ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
      <el-table-column label="操作" width="110">
        <template #default="scope">
          <el-button size="small" @click="openDetail(scope.row.contractNo)">查看详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      layout="prev, pager, next, total"
      :total="total"
      :page-size="pageSize"
      @current-change="loadMine"
    />

    <!-- 发起合约弹窗（W5） -->
    <el-dialog v-model="initiateVisible" title="发起合约" width="680px" class="initiate-dialog">
      <div class="initiate-form">
        <div class="product-row">
          <el-input v-model="productId" placeholder="产品编号" class="product-input" />
          <el-button @click="searchProduct">检索产品</el-button>
        </div>
        <div v-if="searchResults.length" class="search-results">
          <div v-for="item in searchResults" :key="item.productId" class="search-row">
            <span>{{ item.productId }} · {{ item.productName }}（{{ item.providerSubjectNo }}）</span>
            <el-button size="small" @click="pickProduct(item)">选择</el-button>
          </div>
        </div>
        <el-select v-model="templateNo" placeholder="选择模板" class="template-select">
          <el-option
            v-for="tpl in templateOptions"
            :key="tpl.templateNo"
            :label="`${tpl.templateNo} · ${tpl.name}（V${tpl.currentVersion}）`"
            :value="tpl.templateNo"
          />
        </el-select>
        <div v-if="selectedTemplate" class="locked-version">
          锁定模板版本：V{{ selectedTemplate.currentVersion }}
        </div>
        <template v-if="framework">
          <h4>条款内容（按模板框架填写）</h4>
          <ClauseEditor mode="framework" :framework="framework" v-model="clauseSlots" />
          <h4>使用控制策略（五要素，至少启用一项或显式声明无使用限制——由服务端判定）</h4>
          <StrategyEditor v-model="strategy" />
        </template>
      </div>
      <template #footer>
        <el-button @click="initiateVisible = false">取消</el-button>
        <el-button type="primary" @click="submitInitiate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 合约工作台（WBS-3.4.7 hifi §6.3）：我的合约列表（R6）+ 发起弹窗（W5）。发起 = 产品编号
 * 直填 +「检索产品」跨域回填（api/catalog.ts，目录域角色头互不污染）+ 模板下拉（仅启用中，
 * 锁定当前版本）+ 条款框架表单（ClauseEditor 框架模式）+ 策略编辑器（StrategyEditor）；
 * 必填前置（零请求）；拒绝原样展示、状态不变（不做乐观更新）；成功跳转合约详情深链。
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  listMyContracts,
  listTemplates,
  getTemplate,
  initiateContract,
  type MyContract,
  type ContractTemplateItem,
  type ClauseFramework,
} from '../../api/contract'
import { searchProducts, type CatalogProduct } from '../../api/catalog'
import { getDemoSubject } from '../../api/client'
import { CONTRACT_STATUS_LABELS, CONTRACT_STATUS_TAG_TYPES, MY_ROLE_LABELS } from '../../constants/contract'
import ClauseEditor from './components/ClauseEditor.vue'
import StrategyEditor from './components/StrategyEditor.vue'

const router = useRouter()
const pageSize = 10

// ==== 我的合约（R6） ====

const list = ref<MyContract[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const statusFilter = ref<string | undefined>(undefined)
const roleFilter = ref<string | undefined>(undefined)

async function loadMine(): Promise<void> {
  loading.value = true
  try {
    const result = await listMyContracts({
      pageNum: page.value,
      pageSize,
      status: statusFilter.value,
      role: roleFilter.value,
    })
    list.value = result.list
    total.value = result.total
  } catch (err) {
    ElMessage.error((err as Error).message)
  } finally {
    loading.value = false
  }
}

function resetFilter(): void {
  statusFilter.value = undefined
  roleFilter.value = undefined
  page.value = 1
  void loadMine()
}

function openDetail(contractNo: string): void {
  void router.push(`/contracts/workbench/${contractNo}`)
}

// ==== 发起合约（W5） ====

const initiateVisible = ref(false)
const productId = ref('')
const templateNo = ref<string | undefined>(undefined)
const templateOptions = ref<ContractTemplateItem[]>([])
const framework = ref<ClauseFramework | null>(null)
const clauseSlots = ref<{ slots: Record<string, string> }>({ slots: {} })
const strategy = ref<Record<string, unknown>>({})
const searchResults = ref<CatalogProduct[]>([])

async function openInitiate(): Promise<void> {
  productId.value = ''
  templateNo.value = undefined
  framework.value = null
  clauseSlots.value = { slots: {} }
  strategy.value = {}
  searchResults.value = []
  try {
    const result = await listTemplates({ pageNum: 1, pageSize: 100 })
    templateOptions.value = result.list
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
  initiateVisible.value = true
}

async function searchProduct(): Promise<void> {
  try {
    // 跨域只读复用目录域检索（api/catalog.ts 内部带目录域角色头——T18）
    const result = await searchProducts(1, 10, productId.value.trim() || undefined)
    searchResults.value = result.list
    if (result.list.length === 0) {
      ElMessage.info('未检索到产品，可直接填写产品编号')
    }
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
}

function pickProduct(item: CatalogProduct): void {
  productId.value = String(item.productId)
}

const selectedTemplate = computed(() =>
  templateOptions.value.find((tpl) => tpl.templateNo === templateNo.value) ?? null,
)

async function loadFramework(no: string): Promise<void> {
  try {
    const detail = await getTemplate(no)
    framework.value = detail.clauseFramework
  } catch (err) {
    framework.value = null
    ElMessage.error((err as Error).message)
  }
}

watch(templateNo, (no) => {
  framework.value = null
  if (no) {
    void loadFramework(no)
  }
})

async function submitInitiate(): Promise<void> {
  // 必填前置拦截（零请求——hifi §8-1）：产品编号与模板必填
  if (!productId.value.trim() || !templateNo.value) {
    return
  }
  const selected = templateOptions.value.find((tpl) => tpl.templateNo === templateNo.value)
  try {
    const initiated = await initiateContract({
      productId: Number(productId.value.trim()),
      templateNo: templateNo.value,
      templateVersionNo: selected?.currentVersion ?? 1,
      clauseValues: {
        slots: clauseSlots.value.slots,
        strategy: strategy.value as never,
      },
    })
    ElMessage.success(`合约 ${initiated.contractNo} 已发起（${CONTRACT_STATUS_LABELS.NEGOTIATING}）`)
    initiateVisible.value = false
    await router.push(`/contracts/workbench/${initiated.contractNo}`)
  } catch (err) {
    // 原样展示（1008C0010/C0016/C0017/C0014/C0015），弹窗状态不变
    ElMessage.error((err as Error).message)
  }
}

void loadMine()
// 演示身份未设置（空主体）时前置拦截口径统一由 api/contract.ts 承载；此处仅兜底提示
if (!getDemoSubject().trim()) {
  ElMessage.warning('请先在顶栏设置演示身份主体编号')
}
</script>

<style scoped>
.workbench-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.filter-bar,
.operation-bar {
  display: flex;
  gap: 8px;
}

.type-select {
  width: 160px;
}

.initiate-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.product-row {
  display: flex;
  gap: 8px;
}

.product-input {
  max-width: 240px;
}

.search-results {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 8px;
}

.search-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.template-select {
  max-width: 360px;
}

.locked-version {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
