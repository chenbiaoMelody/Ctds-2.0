<template>
  <div class="connector-governance-page">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="全平台连接器" name="connectors">
        <div class="filter-bar">
          <el-select
            v-model="listFilters.status"
            class="governance-status-select"
            clearable
            placeholder="状态"
          >
            <el-option
              v-for="opt in CONNECTOR_STATUS_FILTER_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <el-button @click="loadConnectorList">刷新</el-button>
        </div>
        <div class="overview-bar">
          {{ OVERVIEW_BAR_PREFIX }}{{ ONLINE_LABELS.online }} {{ onlineCount }} /
          {{ ONLINE_LABELS.offline }} {{ offlineCount }}{{ OVERVIEW_BAR_SUFFIX }}
        </div>
        <el-table
          v-loading="listLoading"
          class="governance-table"
          :data="connectorList"
          :empty-text="GOVERNANCE_EMPTY_TEXT"
        >
          <el-table-column label="编号" width="120">
            <template #default="scope">{{ scope.row.connectorNo ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="subjectNo" label="所属主体" width="155" />
          <el-table-column prop="connectorName" label="名称" min-width="150" />
          <el-table-column label="类型" width="110">
            <template #default="scope">
              {{ CONNECTOR_TYPE_LABELS[scope.row.connectorType] ?? scope.row.connectorType }}
            </template>
          </el-table-column>
          <el-table-column prop="connectorVersion" label="当前版本" width="95" />
          <el-table-column label="状态" width="95">
            <template #default="scope">
              <el-tag :type="CONNECTOR_STATUS_TAG_TYPES[scope.row.status] ?? 'info'">
                {{ CONNECTOR_STATUS_LABELS[scope.row.status] ?? scope.row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="在线" width="85">
            <template #default="scope">
              <el-tag :type="onlineTagType(scope.row.status, scope.row.online)">
                {{ onlineLabel(scope.row.status, scope.row.online) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近心跳" width="165">
            <template #default="scope">{{ scope.row.lastHeartbeatAt ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="165" />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="scope">
              <el-button
                v-if="scope.row.connectorNo"
                link
                type="primary"
                @click="openDetail(scope.row.connectorNo)"
              >
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="listTotal > pageSize"
          layout="total, prev, pager, next"
          :total="listTotal"
          :page-size="pageSize"
          :current-page="listPageNum"
          @current-change="onListPageChange"
        />
      </el-tab-pane>

      <el-tab-pane label="注册核验" name="review" lazy>
        <div class="filter-bar">
          <el-button @click="loadReviewQueue">刷新</el-button>
        </div>
        <el-table
          v-loading="queueLoading"
          class="review-table"
          :data="reviewQueue"
          :empty-text="REVIEW_QUEUE_EMPTY_TEXT"
        >
          <el-table-column prop="applicationNo" label="申请单号" width="120" />
          <el-table-column prop="subjectNo" label="所属主体" width="155" />
          <el-table-column prop="connectorName" label="名称" min-width="150" />
          <el-table-column label="类型" width="110">
            <template #default="scope">
              {{ CONNECTOR_TYPE_LABELS[scope.row.connectorType] ?? scope.row.connectorType }}
            </template>
          </el-table-column>
          <el-table-column prop="connectorVersion" label="版本" width="90" />
          <el-table-column prop="createdAt" label="申请时间" width="165" />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="scope">
              <el-button
                link
                type="primary"
                :loading="verifyingApplicationNo === scope.row.applicationNo"
                @click="verify(scope.row.applicationNo)"
              >
                核验
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="queueTotal > pageSize"
          layout="total, prev, pager, next"
          :total="queueTotal"
          :page-size="pageSize"
          :current-page="queuePageNum"
          @current-change="onQueuePageChange"
        />
      </el-tab-pane>

      <el-tab-pane label="升级确认" name="upgrades" lazy>
        <div class="filter-bar">
          <el-button @click="loadPendingUpgrades">刷新</el-button>
        </div>
        <el-table
          v-loading="pendingLoading"
          class="pending-table"
          :data="pendingUpgrades"
          :empty-text="PENDING_UPGRADE_EMPTY_TEXT"
        >
          <el-table-column prop="upgradeNo" label="升级单号" width="115" />
          <el-table-column prop="connectorNo" label="连接器编号" width="120" />
          <el-table-column prop="subjectNo" label="所属主体" width="155" />
          <el-table-column label="版本变更" min-width="150">
            <template #default="scope">{{ scope.row.fromVersion }} → {{ scope.row.toVersion }}</template>
          </el-table-column>
          <el-table-column prop="requestedBy" label="提交人" width="150" />
          <el-table-column prop="createdAt" label="提交时间" width="165" />
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="scope">
              <el-button
                link
                type="primary"
                :loading="confirmingUpgradeNo === scope.row.upgradeNo"
                @click="confirm(scope.row)"
              >
                确认
              </el-button>
              <el-button link type="primary" @click="openDetail(scope.row.connectorNo)">
                查看连接器
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="pendingTotal > pageSize"
          layout="total, prev, pager, next"
          :total="pendingTotal"
          :page-size="pageSize"
          :current-page="pendingPageNum"
          @current-change="onPendingPageChange"
        />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="verifyVisible" title="核验结论" width="520px" destroy-on-close>
      <div v-if="verifyResult" class="verify-dialog">
        <template v-if="verifyResult.verified">
          <div class="verify-passed">{{ VERIFY_PASSED_TIP }}</div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="连接器编号">{{ verifyResult.connectorNo }}</el-descriptions-item>
            <el-descriptions-item label="DID">
              {{ verifyResult.binding?.did ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="建立时间">
              {{ verifyResult.binding?.boundAt ?? '—' }}
            </el-descriptions-item>
          </el-descriptions>
          <el-button
            v-if="verifyResult.connectorNo"
            class="verify-open-connector"
            type="primary"
            @click="openDetail(verifyResult.connectorNo)"
          >
            查看连接器
          </el-button>
        </template>
        <template v-else>
          <div class="verify-rejected">{{ VERIFY_REJECTED_TIP }}</div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="不通过项">
              {{ verifyResult.failedCheck ? (VERIFICATION_CHECK_LABELS[verifyResult.failedCheck] ?? verifyResult.failedCheck) : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="原因码">{{ verifyResult.reasonCode ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="理由">{{ verifyResult.reason ?? '—' }}</el-descriptions-item>
          </el-descriptions>
          <div class="verify-retry-hint">{{ VERIFY_REJECTED_RETRY_HINT }}</div>
        </template>
      </div>
      <template #footer>
        <el-button type="primary" @click="closeVerify">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CONNECTOR_STATUS_FILTER_OPTIONS,
  CONNECTOR_STATUS_LABELS,
  CONNECTOR_STATUS_TAG_TYPES,
  CONNECTOR_TYPE_LABELS,
  CONFIRM_SUCCESS_TIP,
  CONFIRM_UPGRADE_TEXT,
  GOVERNANCE_EMPTY_TEXT,
  ONLINE_LABELS,
  OVERVIEW_BAR_PREFIX,
  OVERVIEW_BAR_SUFFIX,
  PENDING_UPGRADE_EMPTY_TEXT,
  REVIEW_QUEUE_EMPTY_TEXT,
  VERIFICATION_CHECK_LABELS,
  VERIFY_PASSED_TIP,
  VERIFY_REJECTED_RETRY_HINT,
  VERIFY_REJECTED_TIP,
  onlineLabel,
  onlineTagType,
} from '../../constants/connector'
import {
  confirmUpgrade,
  listGovernanceConnectors,
  listPendingUpgrades,
  listReviewQueue,
  verifyRegistration,
  type ConnectorRecord,
  type ConnectorStatus,
  type RegistrationRecord,
  type VerificationResult,
  type VersionChangeRecord,
} from '../../api/connector'
import { getDemoSubject } from '../../api/client'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../../constants/space'

/**
 * WBS-3.6.7「连接器治理」页（hifi §3.3，menuOrder 21，connector.review）：
 * Tab1 全平台连接器（Q3-A 只读端点，状态服务端筛选 + 本页概览条）；
 * Tab2 注册核验（三查结论：通过含编号与绑定摘要；拒绝含不通过项与理由）；
 * Tab3 升级确认（显式确认；不兼容升级确认后生效）。服务端文案原样展示。
 */
const router = useRouter()
const activeTab = ref('connectors')
const pageSize = 10

const listLoading = ref(false)
const connectorList = ref<ConnectorRecord[]>([])
const listTotal = ref(0)
const listPageNum = ref(1)
const listFilters = reactive({ status: '' as ConnectorStatus | '' })

const queueLoading = ref(false)
const reviewQueue = ref<RegistrationRecord[]>([])
const queueTotal = ref(0)
const queuePageNum = ref(1)
const verifyingApplicationNo = ref('')

const pendingLoading = ref(false)
const pendingUpgrades = ref<VersionChangeRecord[]>([])
const pendingTotal = ref(0)
const pendingPageNum = ref(1)
const confirmingUpgradeNo = ref('')

const verifyVisible = ref(false)
const verifyResult = ref<VerificationResult | null>(null)

const onlineCount = computed(
  () =>
    connectorList.value.filter((item) => item.status === 'REGISTERED' && item.online).length,
)
const offlineCount = computed(
  () =>
    connectorList.value.filter((item) => item.status === 'REGISTERED' && !item.online).length,
)

function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error(error instanceof Error ? error.message : String(error))
}

async function loadConnectorList(): Promise<void> {
  listLoading.value = true
  try {
    const data = await listGovernanceConnectors(
      listFilters.status,
      listPageNum.value,
      pageSize,
    )
    connectorList.value = data.list
    listTotal.value = data.total
  } catch (error) {
    showError(error)
  } finally {
    listLoading.value = false
  }
}

async function loadReviewQueue(): Promise<void> {
  queueLoading.value = true
  try {
    const data = await listReviewQueue(queuePageNum.value, pageSize)
    reviewQueue.value = data.list
    queueTotal.value = data.total
  } catch (error) {
    showError(error)
  } finally {
    queueLoading.value = false
  }
}

async function loadPendingUpgrades(): Promise<void> {
  pendingLoading.value = true
  try {
    const data = await listPendingUpgrades(pendingPageNum.value, pageSize)
    pendingUpgrades.value = data.list
    pendingTotal.value = data.total
  } catch (error) {
    showError(error)
  } finally {
    pendingLoading.value = false
  }
}

watch(
  () => listFilters.status,
  () => {
    listPageNum.value = 1
    void loadConnectorList()
  },
)

function onListPageChange(page: number): void {
  listPageNum.value = page
  void loadConnectorList()
}

function onQueuePageChange(page: number): void {
  queuePageNum.value = page
  void loadReviewQueue()
}

function onPendingPageChange(page: number): void {
  pendingPageNum.value = page
  void loadPendingUpgrades()
}

async function verify(applicationNo: string): Promise<void> {
  verifyingApplicationNo.value = applicationNo
  try {
    verifyResult.value = await verifyRegistration(applicationNo)
    verifyVisible.value = true
    await loadReviewQueue()
  } catch (error) {
    showError(error)
    await loadReviewQueue()
  } finally {
    verifyingApplicationNo.value = ''
  }
}

function closeVerify(): void {
  verifyVisible.value = false
}

async function confirm(record: VersionChangeRecord): Promise<void> {
  try {
    await ElMessageBox.confirm(CONFIRM_UPGRADE_TEXT, '升级确认', { type: 'warning' })
  } catch {
    return
  }
  confirmingUpgradeNo.value = record.upgradeNo
  try {
    await confirmUpgrade(record.connectorNo, record.upgradeNo)
    ElMessage.success(CONFIRM_SUCCESS_TIP)
    await loadPendingUpgrades()
  } catch (error) {
    showError(error)
  } finally {
    confirmingUpgradeNo.value = ''
  }
}

function openDetail(connectorNo: string | null): void {
  if (!connectorNo) return
  void router.push(`/connectors/${encodeURIComponent(connectorNo)}?from=governance`)
}

onMounted(() => {
  if (!getDemoSubject().trim()) {
    ElMessage.warning(DEMO_SUBJECT_REQUIRED_TIP)
    return
  }
  void loadConnectorList()
  void loadReviewQueue()
  void loadPendingUpgrades()
})
</script>

<style scoped>
.connector-governance-page {
  padding: 16px;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.governance-status-select {
  width: 130px;
}
.overview-bar {
  margin-bottom: 10px;
  padding: 6px 10px;
  background: #f5f7fa;
  color: #606266;
  font-size: 12px;
}
.verify-passed {
  color: #67c23a;
  font-size: 15px;
  margin-bottom: 10px;
}
.verify-rejected {
  color: #f56c6c;
  font-size: 15px;
  margin-bottom: 10px;
}
.verify-retry-hint {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}
.verify-open-connector {
  margin-top: 10px;
}
</style>
