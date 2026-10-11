<template>
  <div class="template-page">
    <div class="page-tabs">
      <el-button :type="activeTab === 'browse' ? 'primary' : 'default'" @click="switchTab('browse')">浏览</el-button>
      <el-button
        v-if="isOperator"
        :type="activeTab === 'manage' ? 'primary' : 'default'"
        @click="switchTab('manage')"
      >运营维护</el-button>
      <el-button
        v-if="isOperator"
        :type="activeTab === 'logs' ? 'primary' : 'default'"
        @click="switchTab('logs')"
      >维护留痕</el-button>
    </div>

    <!-- 浏览区（R4/R5；所有已入驻主体） -->
    <template v-if="activeTab === 'browse'">
      <div class="filter-bar">
        <el-select v-model="browseType" clearable placeholder="类型" class="type-select">
          <el-option v-for="opt in TEMPLATE_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-button type="primary" @click="loadBrowse">查询</el-button>
        <el-button @click="resetBrowse">重置</el-button>
      </div>
      <el-table v-loading="browseLoading" :data="browseList" :empty-text="`暂无${TEMPLATE_STATUS_LABELS.ENABLED}的模板`">
        <el-table-column prop="templateNo" label="模板编号" width="140" />
        <el-table-column prop="name" label="名称" min-width="200" />
        <el-table-column label="类型" width="150">
          <template #default="scope">{{ scope.row.type.displayName }}</template>
        </el-table-column>
        <el-table-column label="当前版本" width="100">
          <template #default="scope">V{{ scope.row.currentVersion }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="scope">
            <el-button size="small" @click="openDetail(scope.row.templateNo)">查看详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="browsePage"
        layout="prev, pager, next, total"
        :total="browseTotal"
        :page-size="pageSize"
        @current-change="loadBrowse"
      />
    </template>

    <!-- 运营区（R1/R2 + W1~W4；仅运营档可见） -->
    <template v-if="activeTab === 'manage'">
      <div class="operation-bar">
        <el-button type="primary" @click="openCreate">新增模板</el-button>
      </div>
      <div class="filter-bar">
        <el-select v-model="manageStatus" clearable placeholder="状态" class="type-select">
          <el-option v-for="(label, code) in TEMPLATE_STATUS_LABELS" :key="code" :label="label" :value="code" />
        </el-select>
        <el-select v-model="manageType" clearable placeholder="类型" class="type-select">
          <el-option v-for="opt in TEMPLATE_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-button type="primary" @click="searchManage">查询</el-button>
        <el-button @click="resetManage">重置</el-button>
      </div>
      <el-table v-loading="manageLoading" :data="manageList" :empty-text="'暂无模板'">
        <el-table-column prop="templateNo" label="模板编号" width="140" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column label="类型" width="130">
          <template #default="scope">{{ scope.row.type.displayName }}</template>
        </el-table-column>
        <el-table-column label="当前版本" width="90">
          <template #default="scope">V{{ scope.row.currentVersion }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="scope">
            <el-tag :type="TEMPLATE_STATUS_TAG_TYPES[scope.row.status] ?? 'info'">
              {{ TEMPLATE_STATUS_LABELS[scope.row.status] ?? scope.row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdBy" label="创建者" width="130" />
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" min-width="220">
          <template #default="scope">
            <el-button size="small" @click="openRevise(scope.row)">修订</el-button>
            <el-button v-if="scope.row.status === 'ENABLED'" size="small" type="danger" plain
              @click="confirmToggle(scope.row, 'disable')">停用</el-button>
            <el-button v-else size="small" type="success" plain
              @click="confirmToggle(scope.row, 'enable')">启用</el-button>
            <el-button size="small" @click="openVersions(scope.row.templateNo)">版本历史</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="managePage"
        layout="prev, pager, next, total"
        :total="manageTotal"
        :page-size="pageSize"
        @current-change="loadManage"
      />
    </template>

    <!-- 维护留痕（R3；仅运营档） -->
    <template v-if="activeTab === 'logs'">
      <el-table v-loading="logsLoading" :data="logList" :empty-text="'暂无维护记录'">
        <el-table-column label="动作" width="110">
          <template #default="scope">{{ TEMPLATE_ACTION_LOG_LABELS[scope.row.action] ?? scope.row.action }}</template>
        </el-table-column>
        <el-table-column prop="templateNo" label="模板编号" width="130" />
        <el-table-column prop="versionNo" label="版本号" width="90" />
        <el-table-column prop="actorSubjectNo" label="操作者" min-width="170" />
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column label="从何值 → 到何值" min-width="160">
          <template #default="scope">{{ scope.row.fromValue ?? '—' }} → {{ scope.row.toValue ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="reasonCode" label="拒绝码" width="100">
          <template #default="scope">{{ scope.row.reasonCode ?? '—' }}</template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="logsPage"
        layout="prev, pager, next, total"
        :total="logsTotal"
        :page-size="pageSize"
        @current-change="loadLogs"
      />
    </template>

    <!-- 详情抽屉（R5）：模板元数据 + 条款框架表；无数据本体 -->
    <el-drawer v-model="detailVisible" title="模板详情" size="480px">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="模板编号">{{ detail.templateNo }}</el-descriptions-item>
          <el-descriptions-item label="名称">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ detail.type.displayName }}</el-descriptions-item>
          <el-descriptions-item label="当前版本">V{{ detail.currentVersion }}</el-descriptions-item>
          <el-descriptions-item label="发布时间">{{ detail.publishedAt ?? '—' }}</el-descriptions-item>
        </el-descriptions>
        <h4>条款框架</h4>
        <el-table :data="detail.clauseFramework.slots" :empty-text="'（无槽位）'">
          <el-table-column prop="name" label="名称" min-width="120" />
          <el-table-column label="是否必填" width="90">
            <template #default="scope">
              <el-tag v-if="scope.row.required" size="small" type="danger">必填</el-tag>
              <el-tag v-else size="small" type="info">可选</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="guide" label="填写说明" min-width="160">
            <template #default="scope">{{ scope.row.guide ?? '—' }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <!-- 版本历史抽屉（R2）：旧版本保留可查 -->
    <el-drawer v-model="versionsVisible" title="版本历史" size="520px">
      <div v-for="ver in versions" :key="ver.versionNo" class="version-block">
        <div class="version-head">
          <strong>V{{ ver.versionNo }}</strong>
          <span>{{ ver.publishedBy }}</span>
          <span>{{ ver.publishedAt }}</span>
        </div>
        <el-table :data="ver.clauseFramework.slots" size="small" :empty-text="'（无槽位）'">
          <el-table-column prop="name" label="名称" min-width="110" />
          <el-table-column label="必填" width="70">
            <template #default="scope">{{ scope.row.required ? '必填' : '可选' }}</template>
          </el-table-column>
          <el-table-column prop="guide" label="填写说明" min-width="140">
            <template #default="scope">{{ scope.row.guide ?? '—' }}</template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <!-- 新增/修订模板弹窗（W1/W2） -->
    <el-dialog v-model="editorVisible" :title="editorMode === 'create' ? '新增模板' : '修订模板'" width="640px">
      <div class="template-form">
        <el-input v-model="editorName" placeholder="模板名称（≤128 字符）" :maxlength="128" />
        <el-select
          v-model="editorType"
          :disabled="editorMode === 'revise'"
          placeholder="类型"
          class="type-select"
        >
          <el-option v-for="opt in TEMPLATE_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <div class="framework-editor">
          <div v-for="(row, index) in editorSlots" :key="index" class="framework-row">
            <el-input v-model="row.key" placeholder="键（英文标识）" class="row-key" />
            <el-input v-model="row.name" placeholder="名称" />
            <el-checkbox v-model="row.required">必填</el-checkbox>
            <el-input v-model="row.guide" placeholder="填写说明" />
            <el-button size="small" text type="danger" @click="editorSlots.splice(index, 1)">删除</el-button>
          </div>
          <el-button size="small" @click="editorSlots.push({ key: '', name: '', required: false, guide: '' })">新增条款项</el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEditor">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 合约模板页（WBS-3.4.7 hifi §6.2）：浏览区（R4/R5，所有已入驻主体）+ 运营区
 * （R1/R2 + W1~W4，档位 = 运营方）+ 维护留痕页签（R3）。错误展示统一
 * `ElMessage.error(err.message)` 原样（§6.6-2）；同形拒绝共用一条提示常量；
 * 写动作不做乐观更新（一律以响应为准刷新）。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listTemplates,
  getTemplate,
  listManageTemplates,
  listTemplateVersions,
  listTemplateActionLogs,
  createTemplate,
  reviseTemplate,
  disableTemplate,
  enableTemplate,
  type ContractTemplateItem,
  type TemplateDetail,
  type ManageTemplate,
  type TemplateVersionItem,
  type TemplateActionLogItem,
} from '../../api/contract'
import { getActorMode } from '../../stores/demoIdentity'
import {
  TEMPLATE_TYPE_OPTIONS,
  TEMPLATE_STATUS_LABELS,
  TEMPLATE_STATUS_TAG_TYPES,
  TEMPLATE_ACTION_LOG_LABELS,
  TEMPLATE_NOT_ACCESSIBLE_TIP,
} from '../../constants/contract'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'

const router = useRouter()

/** 读面/写面失败统一出口：认证失效（1000C0002）经共享 authGuide 引导回登录页（§6.6-6/§8-10），
 * 其余后端文案原样展示。 */
function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error((error as Error).message)
}

// 档位为体验层显隐（与既有目录域三视图同款 computed 写法；服务端判定为准）
const isOperator = computed(() => getActorMode() === 'operator')
const pageSize = 10

const activeTab = ref<'browse' | 'manage' | 'logs'>('browse')
function switchTab(tab: 'browse' | 'manage' | 'logs'): void {
  activeTab.value = tab
  if (tab === 'browse' && browseList.value.length === 0) void loadBrowse()
  if (tab === 'manage' && manageList.value.length === 0) void loadManage()
  if (tab === 'logs' && logList.value.length === 0) void loadLogs()
}

// ==== 浏览区（R4/R5） ====

const browseType = ref<string | undefined>(undefined)
const browsePage = ref(1)
const browseTotal = ref(0)
const browseLoading = ref(false)
const browseList = ref<ContractTemplateItem[]>([])

async function loadBrowse(): Promise<void> {
  browseLoading.value = true
  try {
    const result = await listTemplates({ pageNum: browsePage.value, pageSize, type: browseType.value })
    browseList.value = result.list
    browseTotal.value = result.total
  } catch (err) {
    showError(err)
  } finally {
    browseLoading.value = false
  }
}

function resetBrowse(): void {
  browseType.value = undefined
  browsePage.value = 1
  void loadBrowse()
}

// ==== 运营区（R1/W1~W4） ====

const manageStatus = ref<string | undefined>(undefined)
const manageType = ref<string | undefined>(undefined)
const managePage = ref(1)
const manageTotal = ref(0)
const manageLoading = ref(false)
const manageList = ref<ManageTemplate[]>([])

async function loadManage(): Promise<void> {
  manageLoading.value = true
  try {
    const result = await listManageTemplates({
      pageNum: managePage.value,
      pageSize,
      status: manageStatus.value,
      type: manageType.value,
    })
    manageList.value = result.list
    manageTotal.value = result.total
  } catch (err) {
    showError(err)
  } finally {
    manageLoading.value = false
  }
}

function searchManage(): void {
  managePage.value = 1
  void loadManage()
}

function resetManage(): void {
  manageStatus.value = undefined
  manageType.value = undefined
  managePage.value = 1
  void loadManage()
}

const detailVisible = ref(false)
const detail = ref<TemplateDetail | null>(null)

async function openDetail(templateNo: string): Promise<void> {
  try {
    detail.value = await getTemplate(templateNo)
    detailVisible.value = true
  } catch (err) {
    // 同形拒绝（1008C0001）：原样展示后端文案；后端文案缺失时以同一条常量兜底（防探测口径一致）
    if (guideToLoginIfAuthFailed(err, router)) return
    ElMessage.error((err as Error).message || TEMPLATE_NOT_ACCESSIBLE_TIP)
  }
}

const editorVisible = ref(false)
const editorMode = ref<'create' | 'revise'>('create')
const editorTargetNo = ref('')
const editorName = ref('')
const editorType = ref<string | undefined>(undefined)
const editorSlots = ref<{ key: string; name: string; required: boolean; guide: string }[]>([])

function openCreate(): void {
  editorMode.value = 'create'
  editorTargetNo.value = ''
  editorName.value = ''
  editorType.value = TEMPLATE_TYPE_OPTIONS[0]?.value
  editorSlots.value = []
  editorVisible.value = true
}

async function openRevise(row: ManageTemplate): Promise<void> {
  try {
    // 修订预载现有条款框架（C-4.1 剧本 S2-1 承载 / hifi §9：既有槽位保留，编辑后发布新版本，
    // 提交为"既有+修订"全量框架——不空开弹窗致既有槽位被静默清空）
    const current = await getTemplate(row.templateNo)
    editorMode.value = 'revise'
    editorTargetNo.value = row.templateNo
    editorName.value = row.name
    editorType.value = row.type.code
    editorSlots.value = current.clauseFramework.slots.map((slot) => ({
      key: slot.key, name: slot.name, required: slot.required, guide: slot.guide ?? '',
    }))
    editorVisible.value = true
  } catch (err) {
    showError(err)
  }
}

async function submitEditor(): Promise<void> {
  // 空框架前置拦截（零请求——hifi §8-1）
  if (!editorName.value.trim() || !editorType.value || editorSlots.value.length === 0) {
    return
  }
  const clauseFramework = {
    slots: editorSlots.value
      .filter((row) => row.key.trim())
      .map((row) => ({ key: row.key.trim(), name: row.name.trim(), required: row.required, guide: row.guide.trim() || null })),
  }
  try {
    if (editorMode.value === 'create') {
      const created = await createTemplate({
        name: editorName.value.trim(), type: editorType.value, clauseFramework,
      })
      ElMessage.success(`模板 ${created.templateNo} 已新增（V${created.versionNo}）`)
    } else {
      const revised = await reviseTemplate(editorTargetNo.value, { clauseFramework })
      ElMessage.success(`修订成功：新版本 V${revised.versionNo}（原 V${revised.previousVersionNo} 保留）`)
    }
    editorVisible.value = false
    await loadManage()
  } catch (err) {
    showError(err)
  }
}

async function confirmToggle(row: ManageTemplate, action: 'disable' | 'enable'): Promise<void> {
  try {
    await ElMessageBox.confirm(
      action === 'disable' ? `确认停用模板 ${row.templateNo}？` : `确认启用模板 ${row.templateNo}？`,
      '二次确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return // 取消 = 零请求（§6.6-4）
  }
  try {
    if (action === 'disable') {
      await disableTemplate(row.templateNo)
    } else {
      await enableTemplate(row.templateNo)
    }
    ElMessage.success('操作成功')
    await loadManage()
  } catch (err) {
    showError(err)
  }
}

// ==== 版本历史（R2） ====

const versionsVisible = ref(false)
const versions = ref<TemplateVersionItem[]>([])

async function openVersions(templateNo: string): Promise<void> {
  try {
    versions.value = await listTemplateVersions(templateNo)
    versionsVisible.value = true
  } catch (err) {
    showError(err)
  }
}

// ==== 维护留痕（R3） ====

const logsPage = ref(1)
const logsTotal = ref(0)
const logsLoading = ref(false)
const logList = ref<TemplateActionLogItem[]>([])

async function loadLogs(): Promise<void> {
  logsLoading.value = true
  try {
    const result = await listTemplateActionLogs({ pageNum: logsPage.value, pageSize })
    logList.value = result.list
    logsTotal.value = result.total
  } catch (err) {
    showError(err)
  } finally {
    logsLoading.value = false
  }
}

void loadBrowse()
</script>

<style scoped>
.template-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.filter-bar {
  display: flex;
  gap: 8px;
}

.type-select {
  width: 180px;
}

.version-block {
  margin-bottom: 16px;
}

.version-head {
  display: flex;
  gap: 12px;
  margin-bottom: 6px;
}

.template-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.framework-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.framework-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.row-key {
  max-width: 180px;
}
</style>
