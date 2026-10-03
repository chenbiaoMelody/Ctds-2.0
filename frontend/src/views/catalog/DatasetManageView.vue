<script setup lang="ts">
/**
 * WBS-3.3.6 页面 3：资源登记（hifi §6.4 / §6.8 弹窗 A/B/C / 测试锚点 T11/T12/T13）：
 * - 我的资源列表（R1，含全部状态）+ 登记弹窗（要素齐备；空间编号可直填 +「我的空间」下拉快捷回填，
 *   非成员/未启用空间由服务端拒绝，界面不拦截提交）；
 * - 变更弹窗（仅可变白名单字段；级别收紧与否以服务端 1007C0004 为准）+ 注销二次确认（不可逆）；
 * - 操作留痕区（R14：四要素 + 从何值→到何值 + 拒绝记录）；
 * - 全部拒绝文案原样展示（不吞不改）；写动作不做乐观更新。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '../../api/client'
import { getActorMode } from '../../stores/demoIdentity'
import {
  registerDataset,
  listMyDatasets,
  updateDataset,
  cancelDataset,
  listCategories,
  listVocabularies,
  listTerms,
  listDatasetActionLogs,
  type DatasetItem,
  type CategoryNode,
  type TagTerm,
  type DatasetActionLog,
} from '../../api/catalog'
import { listSpaces, type SpaceSummary } from '../../api/space'
import {
  DECLARE_CATEGORY_REQUIRED_TIP,
  DECLARE_LEVEL_REQUIRED_TIP,
  RESOURCE_TYPE_REQUIRED_TIP,
  DATASET_TYPE_LABELS,
  DATASET_STATUS_LABELS,
  DATASET_STATUS_TYPES,
  DATASET_ACTION_LABELS,
  ACTION_RESULT_LABELS,
  ACTION_RESULT_TYPES,
  DECLARE_LEVEL_LABELS,
  DECLARE_LEVEL_OPTIONS,
  RESOURCE_NAME_REQUIRED_TIP,
  RESOURCE_INTRO_REQUIRED_TIP,
  SPACE_ID_REQUIRED_TIP,
  SPACE_ID_NUMERIC_TIP,
  OPERATOR_MODE_TIP,
  DATASETS_EMPTY_TIP,
  DATASET_LOGS_EMPTY_TIP,
  datasetCancelConfirmTip,
  labelOf,
} from '../../constants/catalog'
import { SPACE_STATUS_LABELS } from '../../constants/space'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'

const router = useRouter()

const loading = ref(false)
const datasets = ref<DatasetItem[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

const isOperatorMode = computed(() => getActorMode() === 'operator')

// ==== 登记弹窗（弹窗 A） ====
const registerVisible = ref(false)
const submitting = ref(false)
const registerForm = ref({
  name: '',
  type: '',
  intro: '',
  tags: [] as string[],
  declareCategory: '',
  declareLevel: '',
  declareImportant: false,
  spaceId: '',
})
const termOptions = ref<TagTerm[]>([])
const categoryOptions = ref<CategoryNode[]>([])
const spaceOptions = ref<SpaceSummary[]>([])

// ==== 变更弹窗（弹窗 B） ====
const updateVisible = ref(false)
const updating = ref(false)
const updateTarget = ref<DatasetItem | null>(null)
const updateForm = ref({ intro: '', tags: [] as string[], declareCategory: '', declareLevel: '' })

// ==== 留痕区（R14） ====
const logTarget = ref<DatasetItem | null>(null)
const logs = ref<DatasetActionLog[]>([])
const logTotal = ref(0)
const logPageNum = ref(1)
const logLoading = ref(false)

function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error(error instanceof ApiError ? error.message : '请求失败，请稍后重试')
}

async function loadDatasets(): Promise<void> {
  loading.value = true
  try {
    const page = await listMyDatasets(pageNum.value, pageSize.value)
    datasets.value = page.list
    total.value = page.total
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

function flattenCategoryNames(nodes: CategoryNode[]): string[] {
  return nodes.flatMap((node) => [node.categoryName, ...flattenCategoryNames(node.children ?? [])])
}

async function openRegister(): Promise<void> {
  registerForm.value = {
    name: '',
    type: '',
    intro: '',
    tags: [],
    declareCategory: '',
    declareLevel: '',
    declareImportant: false,
    spaceId: '',
  }
  registerVisible.value = true
  try {
    const vocabularies = await listVocabularies()
    const firstVocabulary = vocabularies[0]
    if (firstVocabulary) {
      const termPage = await listTerms(firstVocabulary.vocabularyCode, 1, 100)
      termOptions.value = termPage.list
    }
    categoryOptions.value = await listCategories()
    spaceOptions.value = (await listSpaces(1, 100)).list
  } catch (error) {
    showError(error)
  }
}

async function submitRegister(): Promise<void> {
  const name = registerForm.value.name.trim()
  if (!name) {
    ElMessage.error(RESOURCE_NAME_REQUIRED_TIP)
    return
  }
  if (!registerForm.value.type) {
    ElMessage.error(RESOURCE_TYPE_REQUIRED_TIP)
    return
  }
  if (!registerForm.value.intro.trim()) {
    ElMessage.error(RESOURCE_INTRO_REQUIRED_TIP)
    return
  }
  if (registerForm.value.tags.length === 0) {
    ElMessage.error('请至少选择一个语义标签')
    return
  }
  if (!registerForm.value.declareCategory.trim()) {
    ElMessage.error(DECLARE_CATEGORY_REQUIRED_TIP)
    return
  }
  if (!registerForm.value.declareLevel) {
    ElMessage.error(DECLARE_LEVEL_REQUIRED_TIP)
    return
  }
  const spaceId = registerForm.value.spaceId.trim()
  if (!spaceId) {
    ElMessage.error(SPACE_ID_REQUIRED_TIP)
    return
  }
  if (!/^\d+$/.test(spaceId)) {
    ElMessage.error(SPACE_ID_NUMERIC_TIP)
    return
  }
  submitting.value = true
  try {
    await registerDataset(Number(spaceId), {
      name,
      type: registerForm.value.type,
      intro: registerForm.value.intro.trim(),
      tags: registerForm.value.tags,
      declareCategory: registerForm.value.declareCategory.trim(),
      declareLevel: registerForm.value.declareLevel,
      declareImportant: registerForm.value.declareImportant,
    })
    ElMessage.success('登记成功')
    registerVisible.value = false
    pageNum.value = 1
    await loadDatasets()
  } catch (error) {
    showError(error)
  } finally {
    submitting.value = false
  }
}

function openUpdate(row: DatasetItem): void {
  updateTarget.value = row
  updateForm.value = {
    intro: row.intro,
    tags: [...row.tags],
    declareCategory: row.declareCategory,
    declareLevel: row.declareLevel,
  }
  updateVisible.value = true
}

async function submitUpdate(): Promise<void> {
  const target = updateTarget.value
  if (!target) return
  // 请求体只含可变白名单字段（W2：intro/tags/declareCategory/declareLevel；名称与类型不可变）
  const payload: { intro?: string; tags?: string[]; declareCategory?: string; declareLevel?: string } = {}
  if (updateForm.value.intro.trim() && updateForm.value.intro.trim() !== target.intro) {
    payload.intro = updateForm.value.intro.trim()
  }
  if (JSON.stringify(updateForm.value.tags) !== JSON.stringify(target.tags)) {
    payload.tags = updateForm.value.tags
  }
  if (updateForm.value.declareCategory.trim() !== target.declareCategory) {
    payload.declareCategory = updateForm.value.declareCategory.trim()
  }
  if (updateForm.value.declareLevel !== target.declareLevel) {
    payload.declareLevel = updateForm.value.declareLevel
  }
  if (Object.keys(payload).length === 0) {
    ElMessage.error('请至少修改一项可变字段')
    return
  }
  updating.value = true
  try {
    await updateDataset(target.id, payload)
    ElMessage.success('变更成功')
    updateVisible.value = false
    await loadDatasets()
  } catch (error) {
    showError(error)
  } finally {
    updating.value = false
  }
}

async function onCancel(row: DatasetItem): Promise<void> {
  try {
    await ElMessageBox.confirm(datasetCancelConfirmTip(row.name), '注销资源', {
      confirmButtonText: '确认注销',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await cancelDataset(row.id)
    ElMessage.success("注销成功")
    await loadDatasets()
  } catch (error) {
    showError(error)
  }
}

async function openLogs(row: DatasetItem): Promise<void> {
  logTarget.value = row
  logPageNum.value = 1
  await loadLogs()
}

async function loadLogs(): Promise<void> {
  const target = logTarget.value
  if (!target) return
  logLoading.value = true
  try {
    const page = await listDatasetActionLogs(target.id, logPageNum.value, 10)
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

function statusType(value: string): string {
  return DATASET_STATUS_TYPES[value] ?? 'info'
}

function resultType(value: string): string {
  return ACTION_RESULT_TYPES[value] ?? 'info'
}

function fromToText(row: DatasetActionLog): string {
  if (!row.fromValue && !row.toValue) return '—'
  return `${row.fromValue ?? '—'} → ${row.toValue ?? '—'}`
}



onMounted(() => {
  void loadDatasets()
})
</script>

<template>
  <div class="page">
    <h2 class="page-title">资源登记</h2>
    <p class="page-desc">在已启用的空间内登记数据资源（分类分级申报必填；申报重要数据将被拒收）。</p>

    <el-alert
      v-if="isOperatorMode"
      class="operator-tip"
      :title="OPERATOR_MODE_TIP"
      type="info"
      show-icon
      :closable="false"
    />

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" class="register-open" @click="openRegister">登记资源</el-button>
        <el-button class="refresh-btn" @click="loadDatasets">刷新</el-button>
      </div>

      <el-table :data="datasets" v-loading="loading" :empty-text="DATASETS_EMPTY_TIP">
        <el-table-column prop="dataNo" label="数据标识" min-width="160" />
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column label="类型" width="100">
          <template #default="scope">{{ labelOf(DATASET_TYPE_LABELS, scope.row.type) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="scope">
            <el-tag :type="statusType(scope.row.status)">{{ labelOf(DATASET_STATUS_LABELS, scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="spaceId" label="所属空间" width="110" />
        <el-table-column label="分类分级申报" width="130">
          <template #default="scope">{{ scope.row.declareCategory }} / {{ scope.row.declareLevel }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="登记时间" width="180" />
        <el-table-column label="操作" width="200">
          <template #default="scope">
            <el-button
              v-if="scope.row.status === 'ACTIVE'"
              type="primary"
              link
              class="update-btn"
              @click="openUpdate(scope.row)"
            >变更</el-button>
            <el-button
              v-if="scope.row.status === 'ACTIVE'"
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
        class="datasets-pager"
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="pageNum"
        @current-change="(page: number) => { pageNum = page; void loadDatasets() }"
      />
    </el-card>

    <el-card v-if="logTarget" shadow="never" class="logs-card">
      <h3 class="section-title">操作留痕 — {{ logTarget.name }}（{{ logTarget.dataNo }}）</h3>
      <el-table :data="logs" v-loading="logLoading" :empty-text="DATASET_LOGS_EMPTY_TIP">
        <el-table-column label="动作" width="110">
          <template #default="scope">{{ labelOf(DATASET_ACTION_LABELS, scope.row.action) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="scope">
            <el-tag :type="resultType(scope.row.result)">{{ labelOf(ACTION_RESULT_LABELS, scope.row.result) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="actorSubjectNo" label="操作者" width="170" />
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column label="从何值→到何值" min-width="200">
          <template #default="scope">{{ fromToText(scope.row) }}</template>
        </el-table-column>
        <el-table-column label="拒绝码" width="100">
          <template #default="scope">{{ scope.row.reasonCode ?? '—' }}</template>
        </el-table-column>
      </el-table>
      <el-pagination
        class="dataset-logs-pager"
        layout="prev, pager, next"
        :total="logTotal"
        :page-size="10"
        :current-page="logPageNum"
        @current-change="changeLogPage"
      />
    </el-card>

    <el-dialog v-model="registerVisible" title="登记资源" width="620px" class="register-dialog">
      <el-form label-width="130px">
        <el-form-item label="资源名称" required>
          <el-input v-model="registerForm.name" class="register-name" maxlength="128" placeholder="资源名称（≤128）" />
        </el-form-item>
        <el-form-item label="资源类型" required>
          <el-select v-model="registerForm.type" class="register-type">
            <el-option :label="DATASET_TYPE_LABELS.DATASET" value="DATASET" />
            <el-option :label="DATASET_TYPE_LABELS.API" value="API" />
            <el-option :label="DATASET_TYPE_LABELS.REPORT" value="REPORT" />
            <el-option :label="DATASET_TYPE_LABELS.MODEL" value="MODEL" />
          </el-select>
        </el-form-item>
        <el-form-item label="简介" required>
          <el-input v-model="registerForm.intro" class="register-intro" type="textarea" maxlength="512" :rows="3" />
        </el-form-item>
        <el-form-item label="语义标签" required>
          <el-select
            v-model="registerForm.tags"
            class="register-tags"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="从受控词表选取，可自由输入（词表外将被拒绝）"
          >
            <el-option v-for="term in termOptions" :key="term.termCode" :label="term.termName" :value="term.termName" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类申报" required>
          <el-select
            v-model="registerForm.declareCategory"
            class="register-category"
            filterable
            allow-create
            default-first-option
            placeholder="选择平台受控类目，可自由输入（类目外将被拒绝）"
          >
            <el-option v-for="name in flattenCategoryNames(categoryOptions)" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="级别申报" required>
          <el-select v-model="registerForm.declareLevel" class="register-level">
            <el-option v-for="level in DECLARE_LEVEL_OPTIONS" :key="level" :label="DECLARE_LEVEL_LABELS[level]" :value="level" />
          </el-select>
        </el-form-item>
        <el-form-item label="重要数据申报">
          <el-checkbox v-model="registerForm.declareImportant" class="register-important">
            申报为重要数据（V1.0 一律拒收登记）
          </el-checkbox>
        </el-form-item>
        <el-form-item label="所属空间编号" required>
          <el-select
            v-model="registerForm.spaceId"
            class="register-space"
            filterable
            allow-create
            default-first-option
            placeholder="可直填空间编号，或从我的空间选择"
          >
            <el-option
              v-for="space in spaceOptions"
              :key="space.id"
              :label="`${space.name}（${labelOf(SPACE_STATUS_LABELS, space.status)}）`"
              :value="String(space.id)"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" class="register-submit" :loading="submitting" @click="submitRegister">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="updateVisible" title="变更资源" width="560px" class="update-dialog">
      <el-form label-width="130px">
        <el-form-item label="资源名称">
          <el-input :model-value="updateTarget?.name" disabled />
        </el-form-item>
        <el-form-item label="资源类型">
          <el-input :model-value="labelOf(DATASET_TYPE_LABELS, updateTarget?.type)" disabled />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="updateForm.intro" class="update-intro" type="textarea" maxlength="512" :rows="3" />
        </el-form-item>
        <el-form-item label="语义标签">
          <el-select v-model="updateForm.tags" class="update-tags" multiple filterable allow-create default-first-option>
            <el-option v-for="term in termOptions" :key="term.termCode" :label="term.termName" :value="term.termName" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类申报">
          <el-select v-model="updateForm.declareCategory" class="update-category" filterable allow-create default-first-option>
            <el-option v-for="name in flattenCategoryNames(categoryOptions)" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="级别申报">
          <el-select v-model="updateForm.declareLevel" class="update-level">
            <el-option v-for="level in DECLARE_LEVEL_OPTIONS" :key="level" :label="DECLARE_LEVEL_LABELS[level]" :value="level" />
          </el-select>
          <div class="level-hint">分级只能收紧（就高）：下调方向将被拒绝（服务端判定为准）</div>
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

.datasets-pager,
.dataset-logs-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.register-tags,
.register-category,
.register-space {
  width: 100%;
}

.update-tags,
.update-category {
  width: 100%;
}

.level-hint {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.6;
}
</style>
