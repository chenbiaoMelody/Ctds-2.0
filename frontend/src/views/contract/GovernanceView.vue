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
        <el-descriptions title="合约总览" :column="2" border size="small">
          <el-descriptions-item label="合约编号">{{ detail.detail.contractNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ CONTRACT_STATUS_LABELS[detail.detail.status] ?? detail.detail.status }}
          </el-descriptions-item>
          <el-descriptions-item label="产品名称">{{ detail.detail.productName }}</el-descriptions-item>
          <el-descriptions-item label="定价快照">
            {{ detail.detail.pricingModel }} / {{ detail.detail.priceAmount ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item :label="MY_ROLE_LABELS.PROVIDER">{{ detail.detail.providerSubjectNo }}</el-descriptions-item>
          <el-descriptions-item :label="MY_ROLE_LABELS.REQUESTER">{{ detail.detail.requesterSubjectNo }}</el-descriptions-item>
          <el-descriptions-item label="模板引用">
            {{ detail.detail.templateNo }}（V{{ detail.detail.templateVersionNo }}）
          </el-descriptions-item>
          <el-descriptions-item label="当前版本">V{{ detail.detail.currentClauseVersion }}</el-descriptions-item>
          <el-descriptions-item label="内容哈希">
            <el-tooltip :content="CONTENT_HASH_HINT" placement="top">
              <span class="hash-cell">{{ detail.detail.contentHash }}</span>
            </el-tooltip>
          </el-descriptions-item>
          <el-descriptions-item label="生效 / 完结">
            {{ detail.detail.effectiveAt ?? '—' }} / {{ detail.detail.endedAt ?? '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <h4>当前条款全文</h4>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item
            v-for="(value, key) in detail.detail.clauseValues.slots"
            :key="key"
            :label="String(key)"
          >
            {{ value }}
          </el-descriptions-item>
        </el-descriptions>

        <h4>使用控制策略（五要素全文）</h4>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item :label="POLICY_ELEMENT_LABELS.quota">{{ strategyLine('quota') }}</el-descriptions-item>
          <el-descriptions-item :label="POLICY_ELEMENT_LABELS.term">{{ strategyLine('term') }}</el-descriptions-item>
          <el-descriptions-item label="用途">{{ strategyLine('purpose') }}</el-descriptions-item>
          <el-descriptions-item label="域内范围">{{ strategyLine('territory') }}</el-descriptions-item>
          <el-descriptions-item :label="POLICY_ELEMENT_LABELS.noRedistribution">
            {{ detail.detail.clauseValues.strategy.noRedistribution?.enabled ? '是' : '未启用' }}
          </el-descriptions-item>
          <el-descriptions-item label="无使用限制声明">
            {{ detail.detail.clauseValues.strategy.noRestrictionDeclared ? '是（显式声明）' : '未声明' }}
          </el-descriptions-item>
        </el-descriptions>

        <h4>条款版本链</h4>
        <el-table :data="detail.clauseVersions" size="small" :empty-text="'（仅初始版本）'">
          <el-table-column type="expand">
            <template #default="scope">
              <div class="expand-slots">
                <div v-for="(value, key) in scope.row.clauseValues.slots" :key="key" class="expand-slot-line">
                  {{ key }}：{{ value }}
                </div>
                <span v-if="!Object.keys(scope.row.clauseValues.slots ?? {}).length">—</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="版本号" width="80">
            <template #default="scope">V{{ scope.row.versionNo }}</template>
          </el-table-column>
          <el-table-column prop="proposedBy" label="提案方" width="160">
            <template #default="scope">{{ scope.row.proposedBy ?? '发起' }}</template>
          </el-table-column>
          <el-table-column prop="proposedAt" label="提案时间" width="170">
            <template #default="scope">{{ scope.row.proposedAt ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="变更明细" min-width="180">
            <template #default="scope">
              <div v-for="change in scope.row.changes ?? []" :key="change.slot">
                {{ change.slot }}：{{ change.from ?? '—' }} → {{ change.to ?? '—' }}
              </div>
              <span v-if="!scope.row.changes?.length">—</span>
            </template>
          </el-table-column>
        </el-table>

        <h4>合约留痕</h4>
        <el-table v-loading="logsLoading" :data="actionLogs" size="small" :empty-text="'暂无记录'">
          <el-table-column label="动作" width="110">
            <template #default="scope">{{ ACTION_LOG_ACTION_LABELS[scope.row.action] ?? scope.row.action }}</template>
          </el-table-column>
          <el-table-column label="版本号" width="80">
            <template #default="scope">{{ scope.row.versionNo ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="actorSubjectNo" label="操作者主体编号" min-width="160" />
          <el-table-column prop="createdAt" label="时间" width="170" />
          <el-table-column label="从何值 → 到何值" min-width="160">
            <template #default="scope">{{ scope.row.fromValue ?? '—' }} → {{ scope.row.toValue ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="拒绝码" width="90">
            <template #default="scope">{{ scope.row.reasonCode ?? '—' }}</template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="drawerLogsPage"
          layout="prev, pager, next"
          :total="drawerLogsTotal"
          :page-size="drawerLogsPageSize"
          @current-change="loadLogs"
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
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listGovernanceContracts,
  getGovernanceDetail,
  forceTerminate,
  listContractActionLogs,
  type GovernanceContract,
  type GovernanceDetail,
  type ContractActionLogItem,
} from '../../api/contract'
import {
  POLICY_ELEMENT_LABELS,
  CONTRACT_STATUS_LABELS,
  CONTRACT_STATUS_TAG_TYPES,
  TERMINATION_TYPE_LABELS,
  ACTION_LOG_ACTION_LABELS,
  MY_ROLE_LABELS,
  CONTENT_HASH_HINT,
} from '../../constants/contract'

const pageSize = 10

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
    ElMessage.error((err as Error).message)
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

async function openDetail(contractNo: string): Promise<void> {
  try {
    detail.value = await getGovernanceDetail(contractNo)
    detailNo.value = contractNo
    detailVisible.value = true
  } catch (err) {
    ElMessage.error((err as Error).message)
    return
  }
  drawerLogsPage.value = 1
  await loadLogs()
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
    ElMessage.error((err as Error).message)
  } finally {
    logsLoading.value = false
  }
}

function strategyLine(element: 'quota' | 'term' | 'purpose' | 'territory'): string {
  const el = detail.value?.detail.clauseValues.strategy[element]
  if (!el?.enabled) {
    return '未启用'
  }
  if (element === 'quota') {
    return `${POLICY_ELEMENT_LABELS.quota} ${el.maxCount ?? '—'}`
  }
  if (element === 'term') {
    return `${el.startDate ?? '—'} ~ ${el.endDate ?? '—'}`
  }
  return el.text ?? '已启用'
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
    ElMessage.error((err as Error).message)
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

.hash-cell {
  cursor: help;
}

.expand-slot-line {
  padding: 2px 0;
  font-size: 13px;
}
</style>
