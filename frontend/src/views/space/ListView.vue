<script setup lang="ts">
/**
 * WBS-3.2.6 页面 1：逻辑空间列表（hifi §6.2 / 测试锚点 T5~T9）：
 * - 操作条：创建空间（弹窗 A）+ 我的邀请与申请（独立页入口，不占菜单）；
 * - 检索条：名称关键字（空值 = 不过滤）；
 * - 表格：名称 / 场景类型 / 参与方范围 / 可见性 / 状态（tag）/ 生效期 / 进入详情；
 * - 空态不是报错；错误一律原样展示后端业务文案（不吞不改）。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../api/client'
import { createSpace, listSpaces, type CreateSpacePayload, type SpaceSummary } from '../../api/space'
import {
  ACCESS_MODE_LABELS,
  SCENE_TYPE_LABELS,
  SPACE_LIST_EMPTY_TIP,
  SPACE_NAME_REQUIRED_TIP,
  SPACE_STATUS_LABELS,
  SPACE_STATUS_TYPES,
  VISIBILITY_LABELS,
  labelOf,
} from '../../constants/space'
import { guideToLoginIfAuthFailed } from './authGuide'

const router = useRouter()

const loading = ref(false)
const items = ref<SpaceSummary[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

const keyword = ref('')
const appliedKeyword = ref('')

const createVisible = ref(false)
const submitting = ref(false)
const form = ref({
  name: '',
  sceneType: 'FINTECH',
  accessMode: 'OPEN',
  visibility: 'PUBLIC',
  intro: '',
  effectiveFrom: '',
  effectiveTo: '',
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await listSpaces(pageNum.value, pageSize.value, appliedKeyword.value || undefined)
    items.value = page.list
    total.value = page.total
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    ElMessage.error(error instanceof ApiError ? error.message : '空间列表加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function search(): void {
  pageNum.value = 1
  appliedKeyword.value = keyword.value.trim()
  void load()
}

function reset(): void {
  keyword.value = ''
  appliedKeyword.value = ''
  pageNum.value = 1
  void load()
}

function changePage(page: number): void {
  pageNum.value = page
  void load()
}

function openCreate(): void {
  form.value = {
    name: '',
    sceneType: 'FINTECH',
    accessMode: 'OPEN',
    visibility: 'PUBLIC',
    intro: '',
    effectiveFrom: '',
    effectiveTo: '',
  }
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  const name = form.value.name.trim()
  if (!name) {
    ElMessage.error(SPACE_NAME_REQUIRED_TIP)
    return
  }
  // 请求体只含契约字段（可选字段为空时不提交）
  const payload: CreateSpacePayload = {
    name,
    sceneType: form.value.sceneType,
    accessMode: form.value.accessMode,
    visibility: form.value.visibility,
  }
  if (form.value.intro.trim()) payload.intro = form.value.intro.trim()
  if (form.value.effectiveFrom) payload.effectiveFrom = form.value.effectiveFrom
  if (form.value.effectiveTo) payload.effectiveTo = form.value.effectiveTo

  submitting.value = true
  try {
    await createSpace(payload)
    ElMessage.success('空间已创建（未启用）')
    createVisible.value = false
    pageNum.value = 1
    await load()
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    ElMessage.error(error instanceof ApiError ? error.message : '创建空间失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

function openDetail(row: SpaceSummary): void {
  void router.push(`/spaces/${row.id}`)
}

function openMyAdmissions(): void {
  void router.push('/spaces/my-admissions')
}

function statusType(value: string): string {
  return SPACE_STATUS_TYPES[value] ?? 'info'
}

function periodText(row: SpaceSummary): string {
  if (!row.effectiveFrom && !row.effectiveTo) return '—'
  return `${row.effectiveFrom ?? '—'} ~ ${row.effectiveTo ?? '—'}`
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <h2 class="page-title">逻辑空间</h2>
    <p class="page-desc">查看你有权访问的空间（公开空间或你参与的空间），或按场景创建新空间。</p>

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" class="create-btn" @click="openCreate">创建空间</el-button>
        <el-button class="my-admissions-btn" @click="openMyAdmissions">我的邀请与申请</el-button>
      </div>

      <div class="searchbar">
        <el-input v-model="keyword" class="keyword-input" placeholder="空间名称关键字" clearable />
        <el-button type="primary" class="search-btn" @click="search">查询</el-button>
        <el-button class="reset-btn" @click="reset">重置</el-button>
      </div>

      <el-table :data="items" v-loading="loading" :empty-text="SPACE_LIST_EMPTY_TIP">
        <el-table-column prop="name" label="空间名称" min-width="180" />
        <el-table-column label="场景类型" width="120">
          <template #default="scope">{{ labelOf(SCENE_TYPE_LABELS, scope.row.sceneType) }}</template>
        </el-table-column>
        <el-table-column label="参与方范围" width="120">
          <template #default="scope">{{ labelOf(ACCESS_MODE_LABELS, scope.row.accessMode) }}</template>
        </el-table-column>
        <el-table-column label="可见性" width="100">
          <template #default="scope">{{ labelOf(VISIBILITY_LABELS, scope.row.visibility) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="scope">
            <el-tag :type="statusType(scope.row.status)">{{ labelOf(SPACE_STATUS_LABELS, scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生效期" width="200">
          <template #default="scope">{{ periodText(scope.row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="scope">
            <el-button type="primary" link class="detail-btn" @click="openDetail(scope.row)">进入详情</el-button>
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
    </el-card>

    <el-dialog v-model="createVisible" title="创建空间" width="560px" class="create-dialog">
      <el-form label-width="110px">
        <el-form-item label="空间名称" required>
          <el-input v-model="form.name" class="create-name" maxlength="128" placeholder="空间名称（≤128）" />
        </el-form-item>
        <el-form-item label="场景类型" required>
          <el-select v-model="form.sceneType" class="create-scene">
            <el-option label="普惠金融" value="FINTECH" />
            <el-option label="医疗验证" value="MEDICAL" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="参与方范围" required>
          <el-select v-model="form.accessMode" class="create-access">
            <el-option label="公开" value="OPEN" />
            <el-option label="审批制" value="APPROVAL" />
            <el-option label="邀请制" value="INVITE" />
          </el-select>
        </el-form-item>
        <el-form-item label="可见性" required>
          <el-select v-model="form.visibility" class="create-visibility">
            <el-option label="公开" value="PUBLIC" />
            <el-option label="不公开" value="PRIVATE" />
          </el-select>
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="form.intro" class="create-intro" type="textarea" maxlength="512" :rows="3" />
        </el-form-item>
        <el-form-item label="生效期起">
          <el-date-picker v-model="form.effectiveFrom" class="create-from" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="生效期止">
          <el-date-picker v-model="form.effectiveTo" class="create-to" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" class="create-submit" :loading="submitting" @click="submitCreate">提交</el-button>
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

.toolbar {
  margin-bottom: 12px;
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
