<script setup lang="ts">
/**
 * WBS-3.2.6 页面 4：平台策略治理（hifi §6.5 / 测试锚点 T26）：
 * - 列表（端点 22，分页）；「新建条目」弹窗 G（键限目录三键 + 值 + 红线）；
 * - 「变更」弹窗 H（键只读；值与红线至少一项变更）；
 * - 错误一律原样展示后端文案（如 `1006C0012` 键或值不合目录要求）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../api/client'
import {
  createPlatformPolicy,
  listPlatformPolicies,
  updatePlatformPolicy,
  type PolicyEntry,
} from '../../api/space'
import {
  PLATFORM_POLICIES_EMPTY_TIP,
  POLICY_CATALOG,
  POLICY_KEY_REQUIRED_TIP,
  POLICY_VALUE_REQUIRED_TIP,
  policyDisplayName,
  policyValueLabel,
} from '../../constants/space'

const loading = ref(false)
const items = ref<PolicyEntry[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

const createVisible = ref(false)
const createForm = ref({ entryKey: POLICY_CATALOG[0].entryKey, entryValue: POLICY_CATALOG[0].options[0].value, redline: false })

const editVisible = ref(false)
const editTarget = ref<PolicyEntry | null>(null)
const editForm = ref({ entryValue: '', redline: false })

const createOptions = computed(() => POLICY_CATALOG.find((item) => item.entryKey === createForm.value.entryKey)?.options ?? [])
const editOptions = computed(() => POLICY_CATALOG.find((item) => item.entryKey === editTarget.value?.entryKey)?.options ?? [])

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await listPlatformPolicies(pageNum.value, pageSize.value)
    items.value = page.list
    total.value = page.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '平台策略条目加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  createForm.value = { entryKey: POLICY_CATALOG[0].entryKey, entryValue: POLICY_CATALOG[0].options[0].value, redline: false }
  createVisible.value = true
}

function onCreateKeyChange(key: string): void {
  const entry = POLICY_CATALOG.find((item) => item.entryKey === key)
  createForm.value.entryValue = entry ? entry.options[0].value : ''
}

async function submitCreate(): Promise<void> {
  if (!createForm.value.entryKey) {
    ElMessage.error(POLICY_KEY_REQUIRED_TIP)
    return
  }
  if (!createForm.value.entryValue) {
    ElMessage.error(POLICY_VALUE_REQUIRED_TIP)
    return
  }
  try {
    await createPlatformPolicy({
      entryKey: createForm.value.entryKey,
      entryValue: createForm.value.entryValue,
      redline: createForm.value.redline,
    })
    ElMessage.success('策略条目已新建')
    createVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '新建失败，请稍后重试')
  }
}

function openEdit(row: PolicyEntry): void {
  editTarget.value = row
  editForm.value = { entryValue: row.entryValue, redline: row.redline }
  editVisible.value = true
}

/** 变更（端点 21）：键不可变更，值与红线至少一项（其余由服务端判定）。 */
async function submitEdit(): Promise<void> {
  const target = editTarget.value
  if (!target) return
  const payload: { entryValue?: string; redline?: boolean } = {}
  if (editForm.value.entryValue !== target.entryValue) payload.entryValue = editForm.value.entryValue
  if (editForm.value.redline !== target.redline) payload.redline = editForm.value.redline
  if (Object.keys(payload).length === 0) {
    ElMessage.error('请至少变更值与红线中的一项')
    return
  }
  try {
    await updatePlatformPolicy(target.id, payload)
    ElMessage.success('策略条目已变更')
    editVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '变更失败，请稍后重试')
  }
}

function changePage(page: number): void {
  pageNum.value = page
  void load()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <h2 class="page-title">平台策略治理</h2>
    <p class="page-desc">平台级策略条目的新建与变更（键不可变更；值与红线至少一项）。</p>

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" class="policy-create-open" @click="openCreate">新建条目</el-button>
      </div>
      <el-table :data="items" v-loading="loading" class="platform-policies-table" :empty-text="PLATFORM_POLICIES_EMPTY_TIP">
        <el-table-column prop="entryKey" label="条目键" min-width="170" />
        <el-table-column label="显示名" min-width="140">
          <template #default="scope">{{ scope.row.displayName || policyDisplayName(scope.row.entryKey) }}</template>
        </el-table-column>
        <el-table-column label="值" width="150">
          <template #default="scope">{{ policyValueLabel(scope.row.entryKey, scope.row.entryValue) }}</template>
        </el-table-column>
        <el-table-column label="红线" width="90">
          <template #default="scope">
            <el-tag v-if="scope.row.redline" type="danger" class="redline-tag">红线</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" width="100">
          <template #default="scope">
            <el-button type="primary" link class="policy-edit-btn" @click="openEdit(scope.row)">变更</el-button>
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

    <el-dialog v-model="createVisible" title="新建平台策略条目" width="520px" class="policy-create-dialog">
      <el-form label-width="90px">
        <el-form-item label="条目键" required>
          <el-select v-model="createForm.entryKey" class="policy-create-key" @change="onCreateKeyChange">
            <el-option v-for="item in POLICY_CATALOG" :key="item.entryKey" :label="item.displayName" :value="item.entryKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="值" required>
          <el-select v-model="createForm.entryValue" class="policy-create-value">
            <el-option v-for="item in createOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="红线">
          <el-switch v-model="createForm.redline" class="policy-create-redline" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" class="policy-create-submit" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="变更平台策略条目" width="520px" class="policy-edit-dialog">
      <el-form label-width="90px">
        <el-form-item label="条目键">
          <el-input :model-value="editTarget?.entryKey ?? ''" class="policy-edit-key" disabled />
        </el-form-item>
        <el-form-item label="值">
          <el-select v-model="editForm.entryValue" class="policy-edit-value">
            <el-option v-for="item in editOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="红线">
          <el-switch v-model="editForm.redline" class="policy-edit-redline" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" class="policy-edit-submit" @click="submitEdit">提交</el-button>
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

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
