<template>
  <div class="governance-page">
    <!-- C 留痕说明：每次查看写 GOVERNANCE_VIEW（谁/何时/看哪份合约）——提示文案常驻 -->
    <el-alert
      title="查看即留痕：每次打开治理列表或直查详情都会登记一条治理动作留痕（谁、何时、看哪份合约），合约参与方可在其详情留痕区核对"
      type="info"
      :closable="false"
    />

    <!-- A 全量列表（R10；含协商中/已终止；状态过滤 + 分页） -->
    <div class="filter-bar">
      <el-select v-model="statusFilter" clearable placeholder="状态" class="status-select">
        <el-option v-for="(label, code) in CONTRACT_STATUS_LABELS" :key="code" :label="label" :value="code" />
      </el-select>
      <el-button type="primary" @click="searchList">查询</el-button>
      <el-button @click="resetFilter">重置</el-button>
    </div>
    <el-table v-loading="loading" :data="list" :empty-text="'暂无合约'">
      <el-table-column prop="contractNo" label="合约编号" width="140" />
      <el-table-column prop="productName" label="产品名称" min-width="170" />
      <el-table-column prop="providerSubjectNo" :label="MY_ROLE_LABELS.PROVIDER" min-width="165" />
      <el-table-column prop="requesterSubjectNo" :label="MY_ROLE_LABELS.REQUESTER" min-width="165" />
      <el-table-column label="状态" width="110">
        <template #default="scope">
          <el-tag :type="CONTRACT_STATUS_TAG_TYPES[scope.row.status] ?? 'info'">
            {{ CONTRACT_STATUS_LABELS[scope.row.status] ?? scope.row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="终止类型" width="130">
        <template #default="scope">
          {{ scope.row.terminationType ? TERMINATION_TYPE_LABELS[scope.row.terminationType] ?? scope.row.terminationType : '—' }}
        </template>
      </el-table-column>
      <el-table-column label="当前版本" width="90">
        <template #default="scope">V{{ scope.row.currentClauseVersion }}</template>
      </el-table-column>
      <el-table-column prop="effectiveAt" label="生效时间" width="175">
        <template #default="scope">{{ scope.row.effectiveAt ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="175" />
      <el-table-column label="操作" width="130">
        <template #default="scope">
          <el-button size="small" @click="openDetail(scope.row.contractNo)">查看治理详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      layout="prev, pager, next, total"
      :total="total"
      :page-size="pageSize"
      @current-change="loadList"
    />

    <!-- B 直查详情（R11）：复用 §6.4 总览/条款/版本链/策略/留痕各分区（只读，无动作条） -->
    <el-drawer v-model="detailVisible" title="治理详情" size="720px">
      <template v-if="detail">
        <el-alert
          v-if="detail.detail.terminationType"
          type="error"
          :closable="false"
          :title="`${TERMINATION_TYPE_LABELS[detail.detail.terminationType] ?? detail.detail.terminationType} — 终止理由：${detail.detail.terminationReason ?? '—'}（操作者：${detail.detail.terminatedBy ?? '—'}）`"
        />
        <!-- DB-44：只读五分区收敛到共享组件（治理视角 variant，键名漂移已对齐详情侧口径） -->
        <ContractReadOnlySections
          variant="governance"
          :detail="detail.detail"
          :clause-versions="detail.clauseVersions"
          :framework="framework"
          :action-logs="actionLogs"
          :logs-loading="logsLoading"
          :logs-total="drawerLogsTotal"
          :logs-page="drawerLogsPage"
          :logs-page-size="drawerLogsPageSize"
          @logs-page-change="onLogsPageChange"
        />

        <!-- 治理动作：强制终止（W12；治理页不提供参与方动作与"发起使用"入口——t12 口径） -->
        <div class="governance-actions">
          <el-button
            v-if="detail.detail.status === 'EFFECTIVE'"
            type="danger"
            @click="openForce"
          >强制终止</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 强制终止弹窗（W12）：理由必填（1~512）；留空前置拦截零请求 -->
    <el-dialog v-model="forceVisible" title="强制终止合约" width="480px">
      <div class="force-form">
        <el-input
          v-model="forceReason"
          type="textarea"
          :rows="4"
          :maxlength="512"
          placeholder="强制终止理由（必填，1~512 字符）"
        />
      </div>
      <template #footer>
        <el-button @click="forceVisible = false">取消</el-button>
        <el-button type="danger" @click="submitForce">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 合约治理页（WBS-3.4.7 hifi §6.5；运营档）：全量列表（R10——每次加载服务端写
 * GOVERNANCE_VIEW 留痕，"查看即留痕"提示常驻）+ 按编号直查治理详情（R11——详情 + 版本链 +
 * 留痕区 R13 只读分区，**无参与方动作条、无"发起使用"入口**——服务端 t12 口径）+
 * 强制终止（W12——理由必填 1~512）。非运营档直链 → 服务端 403 原样展示。
 * DB-44 收敛（`docs/designs/DB-44-hifi.md` §2/§3）：抽屉内只读五分区改由共享组件
 * `ContractReadOnlySections`（`variant="governance"`）承载；槽位标签口径与详情侧对齐——
 * 直查时经 Q3-A 加载一次只读模板框架（R5，失败静默回退键名）。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  listGovernanceContracts,
  getGovernanceDetail,
  forceTerminate,
  listContractActionLogs,
  getTemplate,
  type ClauseFramework,
  type GovernanceContract,
  type GovernanceDetail,
  type ContractActionLogItem,
} from '../../api/contract'
import {
  CONTRACT_STATUS_LABELS,
  CONTRACT_STATUS_TAG_TYPES,
  TERMINATION_TYPE_LABELS,
  MY_ROLE_LABELS,
} from '../../constants/contract'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import ContractReadOnlySections from './components/ContractReadOnlySections.vue'

const router = useRouter()
const pageSize = 10

/** 读面加载失败统一出口：认证失效（1000C0002）经共享 authGuide 引导回登录页（§6.6-6/§8-10），
 * 其余后端文案原样展示。 */
function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error((error as Error).message)
}

// ==== 全量列表（R10） ====

const list = ref<GovernanceContract[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const statusFilter = ref<string | undefined>(undefined)

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const result = await listGovernanceContracts({
      pageNum: page.value,
      pageSize,
      status: statusFilter.value,
    })
    list.value = result.list
    total.value = result.total
  } catch (err) {
    showError(err)
  } finally {
    loading.value = false
  }
}

function searchList(): void {
  page.value = 1
  void loadList()
}

function resetFilter(): void {
  statusFilter.value = undefined
  page.value = 1
  void loadList()
}

// ==== 直查详情（R11） ====

const detailVisible = ref(false)
const detail = ref<GovernanceDetail | null>(null)
const detailNo = ref('')
const actionLogs = ref<ContractActionLogItem[]>([])
const logsLoading = ref(false)
const drawerLogsPage = ref(1)
const drawerLogsTotal = ref(0)
const drawerLogsPageSize = 20
/** 模板条款框架（R5）：槽位标签口径与详情页对齐（DB-44 Q3-A）；不可达时回退键名。 */
const framework = ref<ClauseFramework | null>(null)

async function openDetail(contractNo: string): Promise<void> {
  try {
    detail.value = await getGovernanceDetail(contractNo)
    detailNo.value = contractNo
    detailVisible.value = true
  } catch (err) {
    showError(err)
    return
  }
  drawerLogsPage.value = 1
  await loadFramework()
  await loadLogs()
}

/** 加载模板框架（Q3-A：治理直查新增 1 次只读 R5）；失败静默回退键名（与详情页同口径），不影响主面。 */
async function loadFramework(): Promise<void> {
  framework.value = null
  try {
    const tpl = await getTemplate(detail.value?.detail.templateNo ?? '')
    framework.value = tpl.clauseFramework
  } catch {
    // 框架不可达 → 槽位标签回退键名（读面失败不改变主面展示）
  }
}

async function loadLogs(): Promise<void> {
  logsLoading.value = true
  try {
    const result = await listContractActionLogs({
      contractNo: detailNo.value,
      pageNum: drawerLogsPage.value,
      pageSize: drawerLogsPageSize,
    })
    actionLogs.value = result.list
    drawerLogsTotal.value = result.total
  } catch (err) {
    showError(err)
  } finally {
    logsLoading.value = false
  }
}

/** 共享组件留痕翻页回传（DB-44）：更新页号并按既有逻辑重新拉取（无乐观更新）。 */
function onLogsPageChange(page: number): void {
  drawerLogsPage.value = page
  void loadLogs()
}

// ==== 强制终止（W12） ====

const forceVisible = ref(false)
const forceReason = ref('')

function openForce(): void {
  forceReason.value = ''
  forceVisible.value = true
}

async function submitForce(): Promise<void> {
  // 理由必填前置（零请求——hifi §8-1）
  if (!forceReason.value.trim()) {
    return
  }
  try {
    await forceTerminate(detailNo.value, { reason: forceReason.value.trim() })
    ElMessage.success('已强制终止')
    forceVisible.value = false
    await openDetail(detailNo.value)
    await loadList()
  } catch (err) {
    showError(err)
  }
}

void loadList()
</script>

<style scoped>
.governance-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.filter-bar {
  display: flex;
  gap: 8px;
}

.status-select {
  width: 160px;
}

.governance-actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}

.force-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
