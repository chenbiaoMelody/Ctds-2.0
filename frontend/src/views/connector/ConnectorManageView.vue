<template>
  <div class="connector-manage-page">
    <div class="page-header">
      <el-button type="primary" class="apply-btn" @click="openApplyDialog">申请接入</el-button>
      <el-button class="refresh-btn" @click="refreshAll">刷新</el-button>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="注册申请" name="registrations">
        <div class="filter-bar">
          <el-select
            v-model="registrationFilters.status"
            class="registration-status-select"
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
          <el-input
            v-model="registrationFilters.keyword"
            class="registration-keyword"
            clearable
            placeholder="申请单号 / 名称"
          />
          <span class="filter-hint">{{ LOCAL_FILTER_HINT }}</span>
          <el-button @click="resetRegistrationFilter">重置</el-button>
        </div>
        <el-table
          v-loading="registrationLoading"
          class="registration-table"
          :data="filteredRegistrations"
          :empty-text="REGISTRATION_EMPTY_TEXT"
        >
          <el-table-column prop="applicationNo" label="申请单号" width="120" />
          <el-table-column label="连接器编号" width="120">
            <template #default="scope">{{ scope.row.connectorNo ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="connectorName" label="名称" min-width="150" />
          <el-table-column label="类型" width="110">
            <template #default="scope">
              {{ CONNECTOR_TYPE_LABELS[scope.row.connectorType] ?? scope.row.connectorType }}
            </template>
          </el-table-column>
          <el-table-column prop="connectorVersion" label="版本" width="90" />
          <el-table-column label="状态" width="95">
            <template #default="scope">
              <el-tag :type="CONNECTOR_STATUS_TAG_TYPES[scope.row.status] ?? 'info'">
                {{ CONNECTOR_STATUS_LABELS[scope.row.status] ?? scope.row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近拒绝理由" min-width="150">
            <template #default="scope">
              <el-tooltip
                v-if="scope.row.lastRejectionReason"
                :content="scope.row.lastRejectionReason"
                placement="top"
              >
                <span class="ellipsis-text">{{ scope.row.lastRejectionReason }}</span>
              </el-tooltip>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="申请时间" width="165" />
          <el-table-column prop="updatedAt" label="更新时间" width="165" />
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="scope">
              <el-button
                v-if="scope.row.status === 'PENDING_REVIEW'"
                link
                type="primary"
                @click="openAmendDialog(scope.row)"
              >
                补正
              </el-button>
              <el-button
                v-if="scope.row.status === 'PENDING_REVIEW'"
                link
                type="danger"
                @click="withdraw(scope.row)"
              >
                撤回
              </el-button>
              <el-button
                v-if="scope.row.connectorNo"
                link
                type="primary"
                @click="openConnector(scope.row.connectorNo)"
              >
                查看连接器
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="registrationTotal > pageSize"
          layout="total, prev, pager, next"
          :total="registrationTotal"
          :page-size="pageSize"
          :current-page="registrationPageNum"
          @current-change="onRegistrationPageChange"
        />
      </el-tab-pane>

      <el-tab-pane label="我的连接器" name="connectors" lazy>
        <div class="filter-bar">
          <el-select
            v-model="connectorFilters.online"
            class="connector-online-select"
            clearable
            placeholder="在线状态"
          >
            <el-option
              v-for="opt in ONLINE_FILTER_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <el-input
            v-model="connectorFilters.keyword"
            class="connector-keyword"
            clearable
            placeholder="编号 / 名称"
          />
          <span class="filter-hint">{{ LOCAL_FILTER_HINT }}</span>
          <el-button @click="resetConnectorFilter">重置</el-button>
        </div>
        <el-table
          v-loading="connectorLoading"
          class="connector-table"
          :data="filteredConnectors"
          :empty-text="CONNECTOR_EMPTY_TEXT"
        >
          <el-table-column prop="connectorNo" label="编号" width="120" />
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
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="scope">
              <el-button
                v-if="scope.row.connectorNo"
                link
                type="primary"
                @click="openConnector(scope.row.connectorNo)"
              >
                详情
              </el-button>
              <el-button
                v-if="scope.row.connectorNo && scope.row.status === 'REGISTERED'"
                link
                type="primary"
                :loading="heartbeatLoading === scope.row.connectorNo"
                @click="reportHeartbeat(scope.row.connectorNo)"
              >
                上报心跳
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="connectors.length === 0" class="empty-guide">{{ CONNECTOR_EMPTY_GUIDE }}</div>
        <el-pagination
          v-if="connectorTotal > pageSize"
          layout="total, prev, pager, next"
          :total="connectorTotal"
          :page-size="pageSize"
          :current-page="connectorPageNum"
          @current-change="onConnectorPageChange"
        />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="applyVisible" title="申请接入" width="480px" destroy-on-close>
      <el-alert
        v-if="applyError"
        :title="applyError"
        type="error"
        :closable="false"
        class="dialog-error"
      />
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="applyForm.connectorName" class="apply-name" placeholder="连接器名称" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="applyForm.connectorType" class="apply-type">
            <el-option
              v-for="opt in CONNECTOR_TYPE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="applyForm.connectorVersion" class="apply-version" placeholder="1.0.0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" :loading="applySubmitting" @click="submitApply">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="amendVisible" title="补正申请" width="480px" destroy-on-close>
      <el-alert
        v-if="amendError"
        :title="amendError"
        type="error"
        :closable="false"
        class="dialog-error"
      />
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="amendForm.connectorName" class="amend-name" placeholder="连接器名称" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="amendForm.connectorType" class="amend-type">
            <el-option
              v-for="opt in CONNECTOR_TYPE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="amendForm.connectorVersion" class="amend-version" placeholder="1.0.0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="amendVisible = false">取消</el-button>
        <el-button type="primary" :loading="amendSubmitting" @click="submitAmend">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  AMEND_SUCCESS_TIP,
  APPLY_SUCCESS_TIP,
  CONNECTOR_EMPTY_GUIDE,
  CONNECTOR_EMPTY_TEXT,
  CONNECTOR_STATUS_FILTER_OPTIONS,
  CONNECTOR_STATUS_LABELS,
  CONNECTOR_STATUS_TAG_TYPES,
  CONNECTOR_TYPE_LABELS,
  CONNECTOR_TYPE_OPTIONS,
  HEARTBEAT_SUCCESS_TIP,
  LOCAL_FILTER_HINT,
  ONLINE_FILTER_OPTIONS,
  REGISTRATION_EMPTY_TEXT,
  WITHDRAW_CONFIRM_TEXT,
  WITHDRAW_SUCCESS_TIP,
  onlineLabel,
  onlineTagType,
} from '../../constants/connector'
import {
  amendConnector,
  applyConnector,
  heartbeat,
  listMyConnectors,
  listMyRegistrations,
  withdrawRegistration,
  type ConnectorRecord,
  type RegistrationRecord,
} from '../../api/connector'
import { getDemoSubject } from '../../api/client'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../../constants/space'

/**
 * WBS-3.6.7「连接器管理」页（hifi §3.1，menuOrder 20，登录可见）：
 * Tab1 注册申请（本页筛选 / 申请弹窗零前端校验 / 补正 / 撤回二次确认）；
 * Tab2 我的连接器（在线 tag 口径 / 最近心跳 / 上报心跳 / 详情深链）。
 * 服务端文案原样展示；不做乐观更新；无幂等键（按钮 in-flight 禁用仅为防误触）。
 */
const router = useRouter()
const activeTab = ref('registrations')
const pageSize = 10

const registrationLoading = ref(false)
const registrations = ref<RegistrationRecord[]>([])
const registrationTotal = ref(0)
const registrationPageNum = ref(1)
const registrationFilters = reactive({ status: '', keyword: '' })

const connectorLoading = ref(false)
const connectors = ref<ConnectorRecord[]>([])
const connectorTotal = ref(0)
const connectorPageNum = ref(1)
const connectorFilters = reactive({ online: '', keyword: '' })
const heartbeatLoading = ref('')

const applyVisible = ref(false)
const applySubmitting = ref(false)
const applyError = ref('')
const applyForm = reactive({
  connectorName: '',
  connectorType: 'STANDARD' as 'STANDARD',
  connectorVersion: '',
})

const amendVisible = ref(false)
const amendSubmitting = ref(false)
const amendError = ref('')
const amendTarget = ref<RegistrationRecord | null>(null)
const amendForm = reactive({
  connectorName: '',
  connectorType: 'STANDARD' as 'STANDARD',
  connectorVersion: '',
})

const filteredRegistrations = computed(() => {
  const keyword = registrationFilters.keyword.trim().toLowerCase()
  return registrations.value.filter((item) => {
    if (registrationFilters.status && item.status !== registrationFilters.status) return false
    if (!keyword) return true
    return (
      item.applicationNo.toLowerCase().includes(keyword) ||
      item.connectorName.toLowerCase().includes(keyword)
    )
  })
})

const filteredConnectors = computed(() => {
  const keyword = connectorFilters.keyword.trim().toLowerCase()
  return connectors.value.filter((item) => {
    if (connectorFilters.online === 'true' && !item.online) return false
    if (connectorFilters.online === 'false' && item.online) return false
    if (!keyword) return true
    return (
      (item.connectorNo ?? '').toLowerCase().includes(keyword) ||
      item.connectorName.toLowerCase().includes(keyword)
    )
  })
})

function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error(error instanceof Error ? error.message : String(error))
}

async function loadRegistrations(): Promise<void> {
  registrationLoading.value = true
  try {
    const data = await listMyRegistrations(registrationPageNum.value, pageSize)
    registrations.value = data.list
    registrationTotal.value = data.total
  } catch (error) {
    showError(error)
  } finally {
    registrationLoading.value = false
  }
}

async function loadConnectors(): Promise<void> {
  connectorLoading.value = true
  try {
    const data = await listMyConnectors(connectorPageNum.value, pageSize)
    connectors.value = data.list
    connectorTotal.value = data.total
  } catch (error) {
    showError(error)
  } finally {
    connectorLoading.value = false
  }
}

function refreshAll(): void {
  void loadRegistrations()
  void loadConnectors()
}

function resetRegistrationFilter(): void {
  registrationFilters.status = ''
  registrationFilters.keyword = ''
}

function resetConnectorFilter(): void {
  connectorFilters.online = ''
  connectorFilters.keyword = ''
}

function onRegistrationPageChange(page: number): void {
  registrationPageNum.value = page
  void loadRegistrations()
}

function onConnectorPageChange(page: number): void {
  connectorPageNum.value = page
  void loadConnectors()
}

function openApplyDialog(): void {
  applyForm.connectorName = ''
  applyForm.connectorType = 'STANDARD'
  applyForm.connectorVersion = ''
  applyError.value = ''
  applyVisible.value = true
}

async function submitApply(): Promise<void> {
  applySubmitting.value = true
  applyError.value = ''
  try {
    await applyConnector({
      connectorName: applyForm.connectorName,
      connectorType: applyForm.connectorType,
      connectorVersion: applyForm.connectorVersion,
    })
    applyVisible.value = false
    ElMessage.success(APPLY_SUCCESS_TIP)
    await loadRegistrations()
  } catch (error) {
    applyError.value = error instanceof Error ? error.message : String(error)
  } finally {
    applySubmitting.value = false
  }
}

function openAmendDialog(record: RegistrationRecord): void {
  amendTarget.value = record
  amendForm.connectorName = record.connectorName
  // 类型当前仅 STANDARD（后端枚举单值），预填取记录原值以保持三要素回显语义
  amendForm.connectorType = (record.connectorType as 'STANDARD') ?? 'STANDARD'
  amendForm.connectorVersion = record.connectorVersion
  amendError.value = ''
  amendVisible.value = true
}

async function submitAmend(): Promise<void> {
  if (!amendTarget.value) return
  amendSubmitting.value = true
  amendError.value = ''
  try {
    await amendConnector(amendTarget.value.applicationNo, {
      connectorName: amendForm.connectorName,
      connectorType: amendForm.connectorType,
      connectorVersion: amendForm.connectorVersion,
    })
    amendVisible.value = false
    ElMessage.success(AMEND_SUCCESS_TIP)
    await loadRegistrations()
  } catch (error) {
    amendError.value = error instanceof Error ? error.message : String(error)
  } finally {
    amendSubmitting.value = false
  }
}

async function withdraw(record: RegistrationRecord): Promise<void> {
  try {
    await ElMessageBox.confirm(WITHDRAW_CONFIRM_TEXT, '撤回申请', { type: 'warning' })
  } catch {
    return
  }
  try {
    await withdrawRegistration(record.applicationNo)
    ElMessage.success(WITHDRAW_SUCCESS_TIP)
    await loadRegistrations()
  } catch (error) {
    showError(error)
  }
}

async function reportHeartbeat(connectorNo: string): Promise<void> {
  heartbeatLoading.value = connectorNo
  try {
    await heartbeat(connectorNo)
    ElMessage.success(HEARTBEAT_SUCCESS_TIP)
    await loadConnectors()
  } catch (error) {
    showError(error)
  } finally {
    heartbeatLoading.value = ''
  }
}

function openConnector(connectorNo: string | null): void {
  if (!connectorNo) return
  void router.push(`/connectors/${encodeURIComponent(connectorNo)}`)
}

onMounted(() => {
  if (!getDemoSubject().trim()) {
    ElMessage.warning(DEMO_SUBJECT_REQUIRED_TIP)
    return
  }
  refreshAll()
})
</script>

<style scoped>
.connector-manage-page {
  padding: 16px;
}
.page-header {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.filter-hint {
  color: #909399;
  font-size: 12px;
}
.registration-status-select,
.connector-online-select {
  width: 130px;
}
.registration-keyword,
.connector-keyword {
  width: 200px;
}
.ellipsis-text {
  display: inline-block;
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.empty-guide {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}
.dialog-error {
  margin-bottom: 12px;
}
</style>
