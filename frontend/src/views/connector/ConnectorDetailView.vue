<template>
  <div class="connector-detail-page">
    <div class="back-line">
      <el-button link type="primary" class="back-link" @click="goBack">{{ backText }}</el-button>
    </div>

    <div v-if="lifecycleError" class="detail-error">
      <el-alert :title="lifecycleError" type="error" :closable="false" />
      <el-button class="retry-btn" @click="loadLifecycle">重试</el-button>
    </div>

    <template v-if="lifecycle">
      <div class="detail-header">
        <span class="connector-name">{{ lifecycle.connectorName }}</span>
        <span class="connector-no">{{ lifecycle.connectorNo }}</span>
        <el-tag :type="CONNECTOR_STATUS_TAG_TYPES[lifecycle.status] ?? 'info'">
          {{ CONNECTOR_STATUS_LABELS[lifecycle.status] ?? lifecycle.status }}
        </el-tag>
        <el-tag
          v-if="lifecycle.status === 'REGISTERED'"
          :type="onlineTagType(lifecycle.status, lifecycle.online)"
        >
          {{ onlineLabel(lifecycle.status, lifecycle.online) }}
        </el-tag>
      </div>

      <div v-if="isOperatorMode" class="operator-source-hint">{{ OPERATOR_SOURCE_HIDDEN_HINT }}</div>

      <h4 class="section-title">基本信息</h4>
      <el-descriptions :column="3" border size="small" class="basic-section">
        <el-descriptions-item label="连接器编号">{{ lifecycle.connectorNo }}</el-descriptions-item>
        <el-descriptions-item label="所属主体">{{ lifecycle.subjectNo }}</el-descriptions-item>
        <el-descriptions-item label="名称">{{ lifecycle.connectorName }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          {{ CONNECTOR_TYPE_LABELS[lifecycle.connectorType] ?? lifecycle.connectorType }}
        </el-descriptions-item>
        <el-descriptions-item label="当前版本">{{ lifecycle.connectorVersion }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          {{ CONNECTOR_STATUS_LABELS[lifecycle.status] ?? lifecycle.status }}
        </el-descriptions-item>
        <el-descriptions-item label="在线">
          {{ onlineLabel(lifecycle.status, lifecycle.online) }}
        </el-descriptions-item>
        <el-descriptions-item label="最近心跳">{{ lifecycle.lastHeartbeatAt ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ lifecycle.updatedAt }}</el-descriptions-item>
        <el-descriptions-item v-if="lifecycle.cancelledAt" label="注销时间">
          {{ lifecycle.cancelledAt }}
        </el-descriptions-item>
      </el-descriptions>

      <h4 class="section-title">健康度</h4>
      <div class="health-section">
        <el-tag :type="onlineTagType(lifecycle.status, lifecycle.online)">
          {{ onlineLabel(lifecycle.status, lifecycle.online) }}
        </el-tag>
        <span class="health-heartbeat">最近心跳：{{ lifecycle.lastHeartbeatAt ?? '—' }}</span>
        <span class="health-hint">{{ HEALTH_THRESHOLD_HINT }}</span>
        <el-button size="small" @click="loadLifecycle">刷新</el-button>
        <el-button
          v-if="showHeartbeat"
          size="small"
          type="primary"
          :loading="heartbeatSubmitting"
          @click="reportHeartbeat"
        >
          上报心跳
        </el-button>
      </div>

      <h4 class="section-title">DID 绑定</h4>
      <div class="binding-section">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="绑定状态">
            {{ lifecycle.bindingStatus ? (BINDING_STATUS_LABELS[lifecycle.bindingStatus] ?? lifecycle.bindingStatus) : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="DID">{{ lifecycle.did ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="建立时间">{{ lifecycle.boundAt ?? '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="lifecycle.releasedAt" label="解除时间">
            {{ lifecycle.releasedAt }}
          </el-descriptions-item>
        </el-descriptions>
        <div class="binding-actions">
          <el-button
            size="small"
            type="primary"
            :disabled="lifecycle.bindingStatus !== 'ACTIVE'"
            :loading="bindingLoading"
            @click="checkBinding"
          >
            绑定查验
          </el-button>
          <span class="binding-hint">{{ BINDING_CHECK_TRACE_HINT }}</span>
        </div>
        <div v-if="bindingResult" class="binding-result">
          <span>DID 状态：{{ DID_STATUS_LABELS[bindingResult.didStatus] ?? bindingResult.didStatus }}</span>
          <span :class="bindingResult.verified ? 'binding-valid' : 'binding-invalid'">
            {{ bindingResult.verified ? '核验一致' : DID_STATUS_INVALID_TEXT }}
          </span>
        </div>
      </div>

      <h4 class="section-title">版本管理</h4>
      <div class="version-section">
        <div class="version-head">
          <span>当前版本：{{ lifecycle.connectorVersion }}</span>
          <el-button
            v-if="showUpgrade"
            size="small"
            type="primary"
            @click="openUpgradeDialog"
          >
            升级登记
          </el-button>
        </div>
        <el-alert
          v-if="lifecycle.pendingUpgrade"
          :title="UPGRADE_PENDING_TIP"
          type="warning"
          :closable="false"
          class="version-alert"
        />
        <el-table :data="lifecycle.versionHistory" :empty-text="VERSION_HISTORY_EMPTY_TEXT" size="small">
          <el-table-column prop="upgradeNo" label="升级单号" width="110" />
          <el-table-column label="旧版本 → 新版本" min-width="150">
            <template #default="scope">{{ scope.row.fromVersion }} → {{ scope.row.toVersion }}</template>
          </el-table-column>
          <el-table-column label="兼容性" width="90">
            <template #default="scope">
              {{ scope.row.compatibility ? (COMPATIBILITY_LABELS[scope.row.compatibility] ?? scope.row.compatibility) : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="scope">
              <el-tag :type="VERSION_STATUS_TAG_TYPES[scope.row.status] ?? 'info'">
                {{ VERSION_STATUS_LABELS[scope.row.status] ?? scope.row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="requestedBy" label="提交人" width="145" />
          <el-table-column prop="createdAt" label="提交时间" width="165" />
          <el-table-column label="确认人" width="145">
            <template #default="scope">{{ scope.row.confirmedBy ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="生效时间" width="165">
            <template #default="scope">{{ scope.row.appliedAt ?? '—' }}</template>
          </el-table-column>
        </el-table>
      </div>

      <template v-if="showSourceSection">
        <h4 class="section-title">源接入</h4>
        <div class="source-section">
          <div class="source-hints">
            <el-alert :title="GOV_SOURCE_MOCK_HINT" type="info" :closable="false" />
            <el-alert :title="SOURCE_SELF_CHECK_HINT" type="info" :closable="false" />
            <span class="source-mask-hint">{{ SOURCE_CREDENTIAL_MASK_HINT }}</span>
          </div>
          <el-alert
            v-if="sourcesError"
            :title="sourcesError"
            type="error"
            :closable="false"
            class="section-error"
          />
          <div class="source-actions">
            <el-button size="small" type="primary" @click="openSourceRegisterDialog">登记源</el-button>
          </div>
          <el-table :data="sources" :empty-text="SOURCE_EMPTY_TEXT" size="small">
            <el-table-column prop="sourceNo" label="源编号" width="110" />
            <el-table-column label="类型" width="200">
              <template #default="scope">
                {{ SOURCE_TYPE_LABELS[scope.row.sourceType] ?? scope.row.sourceType }}
              </template>
            </el-table-column>
            <el-table-column prop="endpoint" label="源地址" min-width="220" />
            <el-table-column prop="credentialMasked" label="凭据掩码" width="110" />
            <el-table-column prop="updatedAt" label="更新时间" width="165" />
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="scope">
                <el-button
                  link
                  type="primary"
                  :loading="checkingSourceNo === scope.row.sourceNo"
                  @click="runCheck(scope.row.sourceNo)"
                >
                  自检
                </el-button>
                <el-button link type="primary" @click="openSourceAmendDialog(scope.row)">变更</el-button>
                <el-button link type="primary" @click="openRecentCheck(scope.row.sourceNo)">最近自检</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-if="sourcesTotal > sourcesPageSize"
            layout="total, prev, pager, next"
            :total="sourcesTotal"
            :page-size="sourcesPageSize"
            :current-page="sourcesPageNum"
            @current-change="onSourcesPageChange"
          />
        </div>
      </template>

      <h4 class="section-title">动作留痕</h4>
      <div class="audit-section">
        <el-alert
          v-if="logsError"
          :title="logsError"
          type="error"
          :closable="false"
          class="section-error"
        />
        <el-table :data="logs" :empty-text="AUDIT_LOG_EMPTY_TEXT" size="small">
          <el-table-column prop="createdAt" label="时间" width="165" />
          <el-table-column label="动作" width="200">
            <template #default="scope">
              {{ AUDIT_ACTION_LABELS[scope.row.action] ?? scope.row.action }}
            </template>
          </el-table-column>
          <el-table-column label="结果" width="80">
            <template #default="scope">
              <el-tag :type="AUDIT_RESULT_TAG_TYPES[scope.row.result] ?? 'info'">
                {{ AUDIT_RESULT_LABELS[scope.row.result] ?? scope.row.result }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="原因码" width="110">
            <template #default="scope">{{ scope.row.reasonCode ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="operatorNo" label="操作人" width="150" />
          <el-table-column label="详情" min-width="180">
            <template #default="scope">
              <el-tooltip v-if="scope.row.detail" :content="scope.row.detail" placement="top">
                <span class="ellipsis-text">{{ scope.row.detail }}</span>
              </el-tooltip>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="logsTotal > logsPageSize"
          layout="total, prev, pager, next"
          :total="logsTotal"
          :page-size="logsPageSize"
          :current-page="logsPageNum"
          @current-change="onLogsPageChange"
        />
      </div>

      <h4 class="section-title">注销</h4>
      <div class="cancel-section">
        <span class="in-progress-jobs">进行中的交付作业：{{ lifecycle.inProgressJobs }}</span>
        <span v-if="lifecycle.inProgressJobs > 0" class="in-progress-hint">{{ JOB_IN_PROGRESS_HINT }}</span>
        <el-button
          v-if="showCancel"
          size="small"
          type="danger"
          @click="cancelConnectorAction"
        >
          注销连接器
        </el-button>
      </div>
    </template>

    <el-dialog v-model="upgradeVisible" title="升级登记" width="480px" destroy-on-close>
      <el-alert v-if="upgradeError" :title="upgradeError" type="error" :closable="false" class="dialog-error" />
      <el-form label-width="90px">
        <el-form-item label="目标版本">
          <el-input
            v-model="upgradeForm.toVersion"
            class="upgrade-version"
            :placeholder="nextVersionHint"
          />
        </el-form-item>
        <el-form-item label="兼容性">
          <el-select v-model="upgradeForm.compatibility" class="upgrade-compatibility">
            <el-option
              v-for="opt in COMPATIBILITY_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="upgradeVisible = false">取消</el-button>
        <el-button type="primary" :loading="upgradeSubmitting" @click="submitUpgrade">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="sourceRegisterVisible" title="登记源" width="520px" destroy-on-close>
      <el-alert v-if="sourceRegisterError" :title="sourceRegisterError" type="error" :closable="false" class="dialog-error" />
      <el-form label-width="90px">
        <el-form-item label="源类型">
          <el-select v-model="sourceForm.sourceType" class="source-type">
            <el-option
              v-for="opt in SOURCE_TYPE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="支持类型">
          <span class="source-type-hint">{{ sourceTypeHint }}</span>
        </el-form-item>
        <el-form-item label="源地址">
          <el-input v-model="sourceForm.endpoint" class="source-endpoint" placeholder="按源类型填写源地址" />
        </el-form-item>
        <el-form-item label="访问凭据">
          <el-input
            v-model="sourceForm.credential"
            class="source-credential"
            type="password"
            show-password
            placeholder="凭据仅提交服务端，不回显"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sourceRegisterVisible = false">取消</el-button>
        <el-button type="primary" :loading="sourceRegisterSubmitting" @click="submitSourceRegister">
          提交
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="sourceAmendVisible" title="变更源" width="520px" destroy-on-close>
      <el-alert v-if="sourceAmendError" :title="sourceAmendError" type="error" :closable="false" class="dialog-error" />
      <el-form label-width="90px">
        <el-form-item label="源地址">
          <el-input v-model="sourceAmendForm.endpoint" class="source-amend-endpoint" placeholder="源地址" />
        </el-form-item>
        <el-form-item label="访问凭据">
          <el-input
            v-model="sourceAmendForm.credential"
            class="source-amend-credential"
            type="password"
            show-password
            placeholder="留空表示不修改"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sourceAmendVisible = false">取消</el-button>
        <el-button type="primary" :loading="sourceAmendSubmitting" @click="submitSourceAmend">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="checkVisible" title="自检结果" width="440px" destroy-on-close>
      <div v-if="checkResult" class="check-result">
        <div v-if="checkResult.passed" class="check-passed">{{ SELF_CHECK_PASSED }}</div>
        <template v-else>
          <div class="check-failed">
            {{ checkResult.failureType ? (CHECK_FAILURE_LABELS[checkResult.failureType] ?? checkResult.failureType) : SELF_CHECK_FAILED_TIP }}
          </div>
          <div class="check-message">{{ checkResult.message }}</div>
        </template>
        <div class="check-time">{{ checkResult.checkedAt }}</div>
      </div>
      <template #footer>
        <el-button type="primary" @click="checkVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="recentCheckVisible" title="最近自检" size="420px">
      <div class="recent-check">
        <template v-if="recentCheck?.lastCheck">
          <div :class="recentCheck.lastCheck.passed ? 'check-passed' : 'check-failed'">
            {{ recentCheck.lastCheck.passed ? SELF_CHECK_PASSED : (recentCheck.lastCheck.failureType ? (CHECK_FAILURE_LABELS[recentCheck.lastCheck.failureType] ?? recentCheck.lastCheck.failureType) : SELF_CHECK_FAILED_TIP) }}
          </div>
          <div class="check-message">{{ recentCheck.lastCheck.message }}</div>
          <div class="check-time">{{ recentCheck.lastCheck.checkedAt }}</div>
        </template>
        <div v-else class="check-empty">{{ SELF_CHECK_NOT_YET }}</div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  AUDIT_ACTION_LABELS,
  AUDIT_LOG_EMPTY_TEXT,
  AUDIT_RESULT_LABELS,
  AUDIT_RESULT_TAG_TYPES,
  BACK_TO_CONNECTOR_GOVERNANCE,
  BACK_TO_CONNECTOR_MANAGE,
  BINDING_CHECK_TRACE_HINT,
  BINDING_STATUS_LABELS,
  CANCEL_CONFIRM_TEXT,
  CANCEL_SUCCESS_TIP,
  CHECK_FAILURE_LABELS,
  COMPATIBILITY_LABELS,
  COMPATIBILITY_OPTIONS,
  CONNECTOR_STATUS_LABELS,
  CONNECTOR_STATUS_TAG_TYPES,
  CONNECTOR_TYPE_LABELS,
  DID_STATUS_INVALID_TEXT,
  DID_STATUS_LABELS,
  GOV_SOURCE_MOCK_HINT,
  HEALTH_THRESHOLD_HINT,
  HEARTBEAT_SUCCESS_TIP,
  JOB_IN_PROGRESS_HINT,
  OPERATOR_SOURCE_HIDDEN_HINT,
  SELF_CHECK_FAILED_TIP,
  SELF_CHECK_NOT_YET,
  SELF_CHECK_PASSED,
  SOURCE_CREDENTIAL_MASK_HINT,
  SOURCE_AMEND_SUCCESS_TIP,
  SOURCE_EMPTY_TEXT,
  SOURCE_REGISTER_SUCCESS_TIP,
  SOURCE_SELF_CHECK_HINT,
  SOURCE_TYPE_LABELS,
  SOURCE_TYPE_OPTIONS,
  UPGRADE_PENDING_SUCCESS_TIP,
  UPGRADE_RECORDED_TIP,
  UPGRADE_PENDING_TIP,
  VERSION_HISTORY_EMPTY_TEXT,
  VERSION_STATUS_LABELS,
  VERSION_STATUS_TAG_TYPES,
  onlineLabel,
  onlineTagType,
} from '../../constants/connector'
import {
  amendSource,
  cancelConnector,
  checkSource,
  getBinding,
  getLifecycle,
  getSource,
  heartbeat,
  listActionLogs,
  listSources,
  registerSource,
  requestUpgrade,
  type ActionLogRecord,
  type BindingRecord,
  type LifecycleRecord,
  type SourceCheckRecord,
  type SourceRecord,
  type SourceTypeCode,
} from '../../api/connector'
import { getDemoSubject } from '../../api/client'
import { getActorMode } from '../../stores/demoIdentity'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'

/**
 * WBS-3.6.7「连接器详情」深链页（hifi §3.2）：L1 生命周期聚合为单一数据源（进页零 DID 依赖，
 * DID 实时状态经「绑定查验」显式触发）；健康度 / 绑定 / 版本管理 / 源接入（仅普通档属主）/
 * 留痕 / 注销七分区；服务端文案原样展示，不做乐观更新。
 */
const route = useRoute()
const router = useRouter()
const connectorNo = String(route.params.connectorNo ?? '')
const isOperatorMode = getActorMode() === 'operator'
const fromGovernance = route.query.from === 'governance'

const lifecycle = ref<LifecycleRecord | null>(null)
const lifecycleError = ref('')
const logsError = ref('')
const sourcesError = ref('')

const logs = ref<ActionLogRecord[]>([])
const logsTotal = ref(0)
const logsPageNum = ref(1)
const logsPageSize = 10

const sources = ref<SourceRecord[]>([])
const sourcesTotal = ref(0)
const sourcesPageNum = ref(1)
const sourcesPageSize = 10

const bindingLoading = ref(false)
const bindingResult = ref<BindingRecord | null>(null)
const heartbeatSubmitting = ref(false)

const upgradeVisible = ref(false)
const upgradeSubmitting = ref(false)
const upgradeError = ref('')
const upgradeForm = reactive({
  toVersion: '',
  compatibility: 'COMPATIBLE' as 'COMPATIBLE' | 'INCOMPATIBLE',
})

const sourceRegisterVisible = ref(false)
const sourceRegisterSubmitting = ref(false)
const sourceRegisterError = ref('')
const sourceForm = reactive({
  sourceType: 'DATABASE' as SourceTypeCode,
  endpoint: '',
  credential: '',
})

const sourceAmendVisible = ref(false)
const sourceAmendSubmitting = ref(false)
const sourceAmendError = ref('')
const sourceAmendTarget = ref<SourceRecord | null>(null)
const sourceAmendForm = reactive({ endpoint: '', credential: '' })

const checkingSourceNo = ref('')
const checkVisible = ref(false)
const checkResult = ref<SourceCheckRecord | null>(null)

const recentCheckVisible = ref(false)
const recentCheck = ref<SourceRecord | null>(null)

/** 进入来源：治理页深链带 from=governance；运营档从任意深链进入一律回治理页。 */
const returnToGovernance = fromGovernance || isOperatorMode
const backText = returnToGovernance ? BACK_TO_CONNECTOR_GOVERNANCE : BACK_TO_CONNECTOR_MANAGE
const sourceTypeHint = SOURCE_TYPE_OPTIONS.map((opt) => opt.label).join(' / ')

/** 升级目标版本占位 = 当前版本的下一个补丁版本（无当前版本时给通用示例）。 */
const nextVersionHint = computed(() => {
  const current = lifecycle.value?.connectorVersion ?? ''
  const parts = current.split('.')
  if (parts.length === 3 && parts.every((part) => /^\d+$/.test(part))) {
    return `${parts[0]}.${parts[1]}.${Number(parts[2]) + 1}`
  }
  return '如 1.1.0'
})

const isOwner = computed(() => lifecycle.value?.subjectNo === getDemoSubject())
const isRegistered = computed(() => lifecycle.value?.status === 'REGISTERED')

const showHeartbeat = computed(() => !isOperatorMode && isOwner.value && isRegistered.value)
const showUpgrade = computed(() => !isOperatorMode && isOwner.value && isRegistered.value)
const showCancel = computed(
  () => isRegistered.value && (!isOperatorMode ? isOwner.value : true),
)
const showSourceSection = computed(
  () => !isOperatorMode && isOwner.value && isRegistered.value,
)

function showError(error: unknown): void {
  if (guideToLoginIfAuthFailed(error, router)) return
  ElMessage.error(error instanceof Error ? error.message : String(error))
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

async function loadLifecycle(): Promise<void> {
  lifecycleError.value = ''
  try {
    lifecycle.value = await getLifecycle(connectorNo)
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    lifecycleError.value = errorMessage(error)
    return
  }
  await loadLogs()
  if (showSourceSection.value) {
    await loadSources()
  }
}

async function loadLogs(): Promise<void> {
  logsError.value = ''
  try {
    const data = await listActionLogs(connectorNo, logsPageNum.value, logsPageSize)
    logs.value = data.list
    logsTotal.value = data.total
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    logsError.value = errorMessage(error)
  }
}

async function loadSources(): Promise<void> {
  sourcesError.value = ''
  try {
    const data = await listSources(connectorNo, sourcesPageNum.value, sourcesPageSize)
    sources.value = data.list
    sourcesTotal.value = data.total
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    sourcesError.value = errorMessage(error)
  }
}

function onLogsPageChange(page: number): void {
  logsPageNum.value = page
  void loadLogs()
}

function onSourcesPageChange(page: number): void {
  sourcesPageNum.value = page
  void loadSources()
}

function goBack(): void {
  void router.push(returnToGovernance ? '/connector-governance' : '/connectors')
}

async function reportHeartbeat(): Promise<void> {
  heartbeatSubmitting.value = true
  try {
    await heartbeat(connectorNo)
    ElMessage.success(HEARTBEAT_SUCCESS_TIP)
    await loadLifecycle()
  } catch (error) {
    showError(error)
  } finally {
    heartbeatSubmitting.value = false
  }
}

async function checkBinding(): Promise<void> {
  bindingLoading.value = true
  try {
    bindingResult.value = await getBinding(connectorNo)
  } catch (error) {
    showError(error)
  } finally {
    bindingLoading.value = false
  }
}

function openUpgradeDialog(): void {
  upgradeForm.toVersion = ''
  upgradeForm.compatibility = 'COMPATIBLE'
  upgradeError.value = ''
  upgradeVisible.value = true
}

async function submitUpgrade(): Promise<void> {
  upgradeSubmitting.value = true
  upgradeError.value = ''
  try {
    const result = await requestUpgrade(connectorNo, {
      toVersion: upgradeForm.toVersion,
      compatibility: upgradeForm.compatibility,
    })
    upgradeVisible.value = false
    ElMessage.success(
      result.status === 'PENDING_CONFIRM' ? UPGRADE_PENDING_SUCCESS_TIP : UPGRADE_RECORDED_TIP,
    )
    await loadLifecycle()
  } catch (error) {
    upgradeError.value = errorMessage(error)
  } finally {
    upgradeSubmitting.value = false
  }
}

async function cancelConnectorAction(): Promise<void> {
  try {
    await ElMessageBox.confirm(CANCEL_CONFIRM_TEXT, '注销连接器', { type: 'warning' })
  } catch {
    return
  }
  try {
    await cancelConnector(connectorNo)
    ElMessage.success(CANCEL_SUCCESS_TIP)
    await loadLifecycle()
  } catch (error) {
    showError(error)
  }
}

function openSourceRegisterDialog(): void {
  sourceForm.sourceType = 'DATABASE'
  sourceForm.endpoint = ''
  sourceForm.credential = ''
  sourceRegisterError.value = ''
  sourceRegisterVisible.value = true
}

async function submitSourceRegister(): Promise<void> {
  sourceRegisterSubmitting.value = true
  sourceRegisterError.value = ''
  try {
    await registerSource(connectorNo, {
      sourceType: sourceForm.sourceType,
      endpoint: sourceForm.endpoint,
      credential: sourceForm.credential,
    })
    sourceForm.credential = ''
    sourceRegisterVisible.value = false
    ElMessage.success(SOURCE_REGISTER_SUCCESS_TIP)
    await loadSources()
  } catch (error) {
    // 失败不保留已输入的凭据（避免明文在弹窗内停留过久）
    sourceForm.credential = ''
    sourceRegisterError.value = errorMessage(error)
  } finally {
    sourceRegisterSubmitting.value = false
  }
}

function openSourceAmendDialog(item: SourceRecord): void {
  sourceAmendTarget.value = item
  sourceAmendForm.endpoint = item.endpoint
  sourceAmendForm.credential = ''
  sourceAmendError.value = ''
  sourceAmendVisible.value = true
}

async function submitSourceAmend(): Promise<void> {
  if (!sourceAmendTarget.value) return
  sourceAmendSubmitting.value = true
  sourceAmendError.value = ''
  try {
    const body: { endpoint: string; credential?: string } = {
      endpoint: sourceAmendForm.endpoint,
    }
    if (sourceAmendForm.credential) {
      body.credential = sourceAmendForm.credential
    }
    await amendSource(connectorNo, sourceAmendTarget.value.sourceNo, body)
    sourceAmendForm.credential = ''
    sourceAmendVisible.value = false
    ElMessage.success(SOURCE_AMEND_SUCCESS_TIP)
    await loadSources()
  } catch (error) {
    sourceAmendForm.credential = ''
    sourceAmendError.value = errorMessage(error)
  } finally {
    sourceAmendSubmitting.value = false
  }
}

async function runCheck(sourceNo: string): Promise<void> {
  checkingSourceNo.value = sourceNo
  try {
    checkResult.value = await checkSource(connectorNo, sourceNo)
    checkVisible.value = true
  } catch (error) {
    showError(error)
  } finally {
    checkingSourceNo.value = ''
  }
}

async function openRecentCheck(sourceNo: string): Promise<void> {
  try {
    recentCheck.value = await getSource(connectorNo, sourceNo)
    recentCheckVisible.value = true
  } catch (error) {
    showError(error)
  }
}

onMounted(() => {
  void loadLifecycle()
})
</script>

<style scoped>
.connector-detail-page {
  padding: 16px;
}
.back-line {
  margin-bottom: 8px;
}
.detail-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.connector-name {
  font-size: 18px;
  font-weight: 600;
}
.connector-no {
  color: #606266;
}
.section-title {
  margin: 16px 0 8px;
  font-size: 14px;
}
.detail-error {
  display: flex;
  align-items: center;
  gap: 12px;
}
.health-section {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.health-hint,
.binding-hint,
.source-mask-hint,
.in-progress-hint {
  color: #909399;
  font-size: 12px;
}
.binding-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}
.binding-result {
  display: flex;
  gap: 12px;
  margin-top: 8px;
  font-size: 13px;
}
.binding-invalid,
.check-failed {
  color: #f56c6c;
}
.binding-valid {
  color: #67c23a;
}
.check-passed {
  color: #67c23a;
}
.version-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.version-alert {
  margin-bottom: 8px;
}
.operator-source-hint {
  margin-top: 16px;
  color: #909399;
  font-size: 12px;
}
.source-hints {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 8px;
}
.source-actions {
  margin-bottom: 8px;
}
.cancel-section {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ellipsis-text {
  display: inline-block;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.dialog-error,
.section-error {
  margin-bottom: 8px;
}
.check-time,
.check-empty {
  color: #909399;
  font-size: 12px;
  margin-top: 6px;
}
</style>
