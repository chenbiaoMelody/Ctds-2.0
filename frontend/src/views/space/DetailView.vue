<script setup lang="ts">
/**
 * WBS-3.2.6 页面 2：空间详情（hifi §6.3 / 测试锚点 T10~T25、T30b）：
 * - 概览：要素 / 生命周期按钮（启用·冻结·恢复·解散二次确认）/ 修改配置（白名单字段）/ 操作留痕表格；
 * - 成员与准入：成员表格 + 动作条 + 准入单表格（T14~T22）；
 * - 策略：有效策略表格（来源三态 + 红线）+ 覆盖提交（前端不做放宽判定）；
 * - 非成员同形口径：`1006C0004` 与无权（`1006C0007`）两条失败路径渲染**同一条**提示（T30b）；
 * - 权限判定一律在服务端；本页按钮显隐只做体验层，错误文案原样展示、不吞不改。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError, getDemoSubject } from '../../api/client'
import {
  applyAdmission,
  approveAdmission,
  assignRole,
  dissolveSpace,
  enableSpace,
  freezeSpace,
  getEffectivePolicies,
  getSpace,
  inviteMember,
  leaveSpace,
  listActionLogs,
  listAdmissions,
  listMembers,
  removeMember,
  submitPolicyOverride,
  transferOwnership,
  unfreezeSpace,
  updateSpace,
  type AdmissionItem,
  type EffectivePolicyItem,
  type MemberItem,
  type SpaceActionLog,
  type SpaceDetail,
  type SpaceSummary,
  type UpdateSpacePayload,
} from '../../api/space'
import {
  ACTION_LOGS_EMPTY_TIP,
  ACTION_RESULT_LABELS,
  ACTION_RESULT_TYPES,
  ADMISSIONS_EMPTY_TIP,
  ADMISSION_STATUS_LABELS,
  ADMISSION_STATUS_TYPES,
  ADMISSION_TYPE_LABELS,
  EFFECTIVE_POLICIES_EMPTY_TIP,
  INVITE_SUBJECT_REQUIRED_TIP,
  MEMBERS_EMPTY_TIP,
  MEMBER_ROLE_LABELS,
  MEMBER_STATUS_LABELS,
  MEMBER_STATUS_TYPES,
  POLICY_CATALOG,
  POLICY_PROVENANCE_LABELS,
  POLICY_PROVENANCE_TYPES,
  REJECT_REASON_REQUIRED_TIP,
  REMOVAL_REASON_REQUIRED_TIP,
  SPACE_NOT_ACCESSIBLE_CODES,
  SPACE_NOT_ACCESSIBLE_TIP,
  SPACE_STATUS_LABELS,
  SPACE_STATUS_TYPES,
  actionLogLabel,
  policyValueLabel,
  spaceDissolveConfirmTip,
  spacePolicyBlockTip,
} from '../../constants/space'

const route = useRoute()
const router = useRouter()
const spaceId = Number(route.params.id)

const activeTab = ref('overview')
const notAccessible = ref(false)
const detail = ref<SpaceDetail | null>(null)
const summary = ref<SpaceSummary | null>(null)

const logs = ref<SpaceActionLog[]>([])
const logsTotal = ref(0)
const logsPage = ref(1)
const pageSize = ref(10)

const members = ref<MemberItem[]>([])
const membersTotal = ref(0)
const membersPage = ref(1)

const admissions = ref<AdmissionItem[]>([])
const admissionsTotal = ref(0)
const admissionsPage = ref(1)
const admissionStatus = ref('')

const policies = ref<EffectivePolicyItem[]>([])

// ==== 派生（状态与身份只影响体验层显隐，服务端为准） ====

const current = computed<SpaceSummary | null>(() => detail.value ?? summary.value)
const statusLabel = computed(() => SPACE_STATUS_LABELS[current.value?.status ?? ''] ?? current.value?.status ?? '')
const statusType = computed(() => SPACE_STATUS_TYPES[current.value?.status ?? ''] ?? 'info')
const canEnable = computed(() => current.value?.status === 'CREATED')
const canFreeze = computed(() => current.value?.status === 'ACTIVE')
const canUnfreeze = computed(() => current.value?.status === 'FROZEN')
const canDissolve = computed(() => !!current.value && current.value.status !== 'DISSOLVED')
const isOwner = computed(() => !!detail.value && getDemoSubject() === detail.value.ownerSubjectNo)
const policyBlocked = computed(() => !!current.value && current.value.status !== 'ACTIVE')
const policyBlockTip = computed(() => spacePolicyBlockTip(current.value?.status ?? ''))
const overrideOptions = computed(() => POLICY_CATALOG.find((item) => item.entryKey === overrideForm.value.entryKey)?.options ?? [])
const admissionStatusOptions = computed(() =>
  Object.entries(ADMISSION_STATUS_LABELS).map(([value, label]) => ({ value, label })),
)

// ==== 概览 ====

async function loadDetail(): Promise<void> {
  try {
    const data = await getSpace(spaceId)
    if ('ownerSubjectNo' in data) {
      detail.value = data as SpaceDetail
      summary.value = null
    } else {
      // 非成员可见的公开摘要：与"不存在/无权"共用同一条提示与样式（防存在性探测）
      summary.value = data
      detail.value = null
      notAccessible.value = true
    }
  } catch (error) {
    if (error instanceof ApiError && SPACE_NOT_ACCESSIBLE_CODES.includes(error.code)) {
      notAccessible.value = true
      return
    }
    ElMessage.error(error instanceof ApiError ? error.message : '空间详情加载失败，请稍后重试')
  }
}

async function loadLogs(): Promise<void> {
  try {
    const page = await listActionLogs(spaceId, logsPage.value, pageSize.value)
    logs.value = page.list
    logsTotal.value = page.total
  } catch (error) {
    if (error instanceof ApiError && SPACE_NOT_ACCESSIBLE_CODES.includes(error.code)) return
    ElMessage.error(error instanceof ApiError ? error.message : '操作留痕加载失败，请稍后重试')
  }
}

async function runLifecycle(kind: 'enable' | 'freeze' | 'unfreeze'): Promise<void> {
  const call = kind === 'enable' ? enableSpace : kind === 'freeze' ? freezeSpace : unfreezeSpace
  const tip = kind === 'enable' ? '空间已启用' : kind === 'freeze' ? '空间已冻结' : '空间已恢复'
  try {
    await call(spaceId)
    ElMessage.success(tip)
    await refreshOverview()
  } catch (error) {
    showError(error, '操作失败，请稍后重试')
  }
}

/** 解散两段式：取消 = 零请求（T12）。 */
async function dissolve(): Promise<void> {
  try {
    await ElMessageBox.confirm(spaceDissolveConfirmTip(current.value?.name ?? ''), '解散空间', {
      confirmButtonText: '确认解散',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await dissolveSpace(spaceId)
    ElMessage.success('空间已解散')
    await refreshOverview()
  } catch (error) {
    showError(error, '解散失败，请稍后重试')
  }
}

const configVisible = ref(false)
const configForm = ref({ intro: '', effectiveFrom: '', effectiveTo: '' })

function openConfig(): void {
  configForm.value = {
    intro: detail.value?.intro ?? '',
    effectiveFrom: detail.value?.effectiveFrom ?? '',
    effectiveTo: detail.value?.effectiveTo ?? '',
  }
  configVisible.value = true
}

/** 配置变更：请求体**只含白名单字段**（T13）。 */
async function submitConfig(): Promise<void> {
  const payload: UpdateSpacePayload = {}
  if (configForm.value.intro.trim()) payload.intro = configForm.value.intro.trim()
  if (configForm.value.effectiveFrom) payload.effectiveFrom = configForm.value.effectiveFrom
  if (configForm.value.effectiveTo) payload.effectiveTo = configForm.value.effectiveTo
  try {
    await updateSpace(spaceId, payload)
    ElMessage.success('配置已变更')
    configVisible.value = false
    await refreshOverview()
  } catch (error) {
    showError(error, '配置变更失败，请稍后重试')
  }
}

// ==== 成员与准入 ====

async function loadMembers(): Promise<void> {
  try {
    const page = await listMembers(spaceId, membersPage.value, pageSize.value)
    members.value = page.list
    membersTotal.value = page.total
  } catch (error) {
    if (error instanceof ApiError && SPACE_NOT_ACCESSIBLE_CODES.includes(error.code)) return
    ElMessage.error(error instanceof ApiError ? error.message : '成员列表加载失败，请稍后重试')
  }
}

async function loadAdmissions(): Promise<void> {
  try {
    const page = await listAdmissions(spaceId, admissionsPage.value, pageSize.value, admissionStatus.value || undefined)
    admissions.value = page.list
    admissionsTotal.value = page.total
  } catch (error) {
    if (error instanceof ApiError && SPACE_NOT_ACCESSIBLE_CODES.includes(error.code)) return
    ElMessage.error(error instanceof ApiError ? error.message : '准入单加载失败，请稍后重试')
  }
}

const inviteVisible = ref(false)
const inviteForm = ref({ subjectNo: '', reason: '' })

function openInvite(): void {
  inviteForm.value = { subjectNo: '', reason: '' }
  inviteVisible.value = true
}

/** 邀请成员（T15）：成功提示"待被邀方确认"，未成为成员前不出现成员行。 */
async function submitInvite(): Promise<void> {
  const subjectNo = inviteForm.value.subjectNo.trim()
  if (!subjectNo) {
    ElMessage.error(INVITE_SUBJECT_REQUIRED_TIP)
    return
  }
  const payload: { subjectNo: string; reason?: string } = { subjectNo }
  if (inviteForm.value.reason.trim()) payload.reason = inviteForm.value.reason.trim()
  try {
    await inviteMember(spaceId, payload)
    ElMessage.success('邀请已发出，待被邀方确认')
    inviteVisible.value = false
    await Promise.all([loadAdmissions(), loadMembers()])
  } catch (error) {
    showError(error, '邀请失败，请稍后重试')
  }
}

/** 提交加入申请（T16）：成功进入"待审批"，不直接成为成员。 */
async function submitApplication(): Promise<void> {
  try {
    await applyAdmission(spaceId)
    ElMessage.success('加入申请已提交，待审批')
    await Promise.all([loadAdmissions(), loadMembers()])
  } catch (error) {
    showError(error, '申请提交失败，请稍后重试')
  }
}

const approvalVisible = ref(false)
const approvalTarget = ref<AdmissionItem | null>(null)
const approvalForm = ref({ decision: 'APPROVE', reason: '' })

function openApproval(row: AdmissionItem): void {
  approvalTarget.value = row
  approvalForm.value = { decision: 'APPROVE', reason: '' }
  approvalVisible.value = true
}

/** 审批（T17）：拒绝理由必填 → 留空前置拦截（零请求）。 */
async function submitApproval(): Promise<void> {
  const target = approvalTarget.value
  if (!target) return
  const payload: { decision: 'APPROVE' | 'REJECT'; reason?: string } = {
    decision: approvalForm.value.decision === 'REJECT' ? 'REJECT' : 'APPROVE',
  }
  if (payload.decision === 'REJECT') {
    const reason = approvalForm.value.reason.trim()
    if (!reason) {
      ElMessage.error(REJECT_REASON_REQUIRED_TIP)
      return
    }
    payload.reason = reason
  }
  try {
    await approveAdmission(spaceId, target.id, payload)
    ElMessage.success(payload.decision === 'REJECT' ? '已拒绝该申请' : '已通过该申请')
    approvalVisible.value = false
    await Promise.all([loadAdmissions(), loadMembers()])
  } catch (error) {
    showError(error, '审批失败，请稍后重试')
  }
}

const removalVisible = ref(false)
const removalTarget = ref<MemberItem | null>(null)
const removalReason = ref('')

function openRemoval(row: MemberItem): void {
  removalTarget.value = row
  removalReason.value = ''
  removalVisible.value = true
}

/** 移除成员（T20）：理由必填 → 留空前置拦截（零请求）。 */
async function submitRemoval(): Promise<void> {
  const target = removalTarget.value
  if (!target) return
  const reason = removalReason.value.trim()
  if (!reason) {
    ElMessage.error(REMOVAL_REASON_REQUIRED_TIP)
    return
  }
  try {
    await removeMember(spaceId, target.id, reason)
    ElMessage.success('成员已移除')
    removalVisible.value = false
    await Promise.all([loadMembers(), loadAdmissions()])
  } catch (error) {
    showError(error, '移除失败，请稍后重试')
  }
}

/** 角色授予 / 收回（T19）：危险动作二次确认，取消 = 零请求。 */
async function changeRole(row: MemberItem, role: 'ADMIN' | 'MEMBER'): Promise<void> {
  const isGrant = role === 'ADMIN'
  try {
    await ElMessageBox.confirm(
      isGrant ? '确认授予该成员管理员角色？' : '确认收回该成员管理员角色？',
      '角色变更',
      { confirmButtonText: '确认', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await assignRole(spaceId, row.id, role)
    ElMessage.success('角色已变更')
    await loadMembers()
  } catch (error) {
    showError(error, '角色变更失败，请稍后重试')
  }
}

/** 退出空间（T21）：OWNER 行被服务端拒绝 → 原样展示后端文案。 */
async function leave(): Promise<void> {
  try {
    await leaveSpace(spaceId)
    ElMessage.success('已退出空间')
    await Promise.all([loadMembers(), loadDetail()])
  } catch (error) {
    showError(error, '退出失败，请稍后重试')
  }
}

/** 转移所有权（T22）：目标为成员行 id；危险动作二次确认。 */
async function transfer(row: MemberItem): Promise<void> {
  try {
    await ElMessageBox.confirm('确认把空间所有权转移给该成员？转移后你将变为普通成员。', '转移所有权', {
      confirmButtonText: '确认转移',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await transferOwnership(spaceId, row.id)
    ElMessage.success('所有权已转移')
    await Promise.all([loadMembers(), loadDetail()])
  } catch (error) {
    showError(error, '转移失败，请稍后重试')
  }
}

// ==== 策略 ====

async function loadPolicies(): Promise<void> {
  try {
    policies.value = await getEffectivePolicies(spaceId)
  } catch (error) {
    if (error instanceof ApiError && SPACE_NOT_ACCESSIBLE_CODES.includes(error.code)) return
    ElMessage.error(error instanceof ApiError ? error.message : '有效策略加载失败，请稍后重试')
  }
}

const overrideForm = ref({ entryKey: POLICY_CATALOG[0].entryKey, entryValue: POLICY_CATALOG[0].options[0].value })

watch(() => overrideForm.value.entryKey, (key) => {
  const entry = POLICY_CATALOG.find((item) => item.entryKey === key)
  overrideForm.value.entryValue = entry ? entry.options[0].value : ''
})

/** 覆盖提交（T24/T25）：前端不做放宽判定；被拒原样展示且表格不变（不乐观更新）。 */
async function submitOverride(): Promise<void> {
  try {
    await submitPolicyOverride(spaceId, {
      entryKey: overrideForm.value.entryKey,
      entryValue: overrideForm.value.entryValue,
    })
    ElMessage.success('策略覆盖已提交')
    await loadPolicies()
  } catch (error) {
    showError(error, '策略覆盖失败，请稍后重试')
  }
}

// ==== 公用 ====

function showError(error: unknown, fallback: string): void {
  ElMessage.error(error instanceof ApiError ? error.message : fallback)
}

function valueChange(row: SpaceActionLog): string {
  if (!row.fromValue && !row.toValue) return '—'
  return `${row.fromValue ?? '—'} → ${row.toValue ?? '—'}`
}

function backToList(): void {
  void router.push('/spaces')
}

async function refreshOverview(): Promise<void> {
  await Promise.all([loadDetail(), loadLogs()])
}

function changeLogsPage(page: number): void {
  logsPage.value = page
  void loadLogs()
}

function changeMembersPage(page: number): void {
  membersPage.value = page
  void loadMembers()
}

function changeAdmissionsPage(page: number): void {
  admissionsPage.value = page
  void loadAdmissions()
}

function filterAdmissions(): void {
  admissionsPage.value = 1
  void loadAdmissions()
}

onMounted(() => {
  void refreshOverview()
  void loadMembers()
  void loadAdmissions()
  void loadPolicies()
})
</script>

<template>
  <div class="page">
    <div class="detail-header">
      <el-button link class="back-btn" @click="backToList">返回列表</el-button>
      <h2 class="space-name">{{ current?.name ?? '' }}</h2>
      <el-tag v-if="current" :type="statusType" class="space-status">{{ statusLabel }}</el-tag>
    </div>

    <el-alert
      v-if="notAccessible"
      class="not-accessible-tip"
      :title="SPACE_NOT_ACCESSIBLE_TIP"
      type="warning"
      show-icon
      :closable="false"
    />

    <el-tabs v-model="activeTab" class="detail-tabs">
      <el-tab-pane label="概览" name="overview">
        <el-card shadow="never" class="overview-card">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="空间名称">{{ current?.name ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ statusLabel || '—' }}</el-descriptions-item>
            <el-descriptions-item label="场景类型">{{ current?.sceneType ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="参与方范围">{{ current?.accessMode ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="可见性">{{ current?.visibility ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="简介">{{ current?.intro ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="生效期起">{{ current?.effectiveFrom ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="生效期止">{{ current?.effectiveTo ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="所有者主体编号">{{ detail?.ownerSubjectNo ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ detail?.createdAt ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ detail?.updatedAt ?? '—' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card shadow="never" class="lifecycle-card">
          <template #header>生命周期</template>
          <el-button v-if="canEnable" type="primary" class="lifecycle-enable" @click="runLifecycle('enable')">启用</el-button>
          <el-button v-if="canFreeze" class="lifecycle-freeze" @click="runLifecycle('freeze')">冻结</el-button>
          <el-button v-if="canUnfreeze" class="lifecycle-unfreeze" @click="runLifecycle('unfreeze')">恢复</el-button>
          <el-button v-if="canDissolve" type="danger" class="lifecycle-dissolve" @click="dissolve">解散</el-button>
          <el-button class="config-open" @click="openConfig">修改配置</el-button>
        </el-card>

        <el-card shadow="never" class="logs-card">
          <template #header>操作留痕</template>
          <el-table :data="logs" class="action-logs" :empty-text="ACTION_LOGS_EMPTY_TIP">
            <el-table-column label="动作" width="140">
              <template #default="scope">{{ actionLogLabel(scope.row.action) }}</template>
            </el-table-column>
            <el-table-column prop="operator" label="操作者" min-width="160" />
            <el-table-column prop="createdAt" label="时间" width="180" />
            <el-table-column label="结果" width="110">
              <template #default="scope">
                <el-tag :type="ACTION_RESULT_TYPES[scope.row.result] ?? 'info'">
                  {{ ACTION_RESULT_LABELS[scope.row.result] ?? scope.row.result }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="理由" min-width="160">
              <template #default="scope">{{ scope.row.reason ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="值变化" min-width="160">
              <template #default="scope">{{ valueChange(scope.row) }}</template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="logs-pager"
            layout="prev, pager, next"
            :total="logsTotal"
            :page-size="pageSize"
            :current-page="logsPage"
            @current-change="changeLogsPage"
          />
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="成员与准入" name="members">
        <el-card shadow="never" class="members-card">
          <template #header>成员</template>
          <div class="members-actions">
            <el-button v-if="current?.accessMode === 'INVITE'" class="invite-open" @click="openInvite">邀请成员</el-button>
            <el-button v-if="current && current.accessMode !== 'INVITE'" class="apply-btn" @click="submitApplication">提交加入申请</el-button>
            <el-button class="leave-btn" @click="leave">退出空间</el-button>
          </div>
          <el-table :data="members" class="members-table" :empty-text="MEMBERS_EMPTY_TIP">
            <el-table-column prop="id" label="成员 id" width="90" />
            <el-table-column prop="subjectNo" label="主体编号" min-width="170" />
            <el-table-column label="角色" width="100">
              <template #default="scope">{{ MEMBER_ROLE_LABELS[scope.row.role] ?? scope.row.role }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="scope">
                <el-tag :type="MEMBER_STATUS_TYPES[scope.row.status] ?? 'info'">
                  {{ MEMBER_STATUS_LABELS[scope.row.status] ?? scope.row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="joinedAt" label="加入时间" width="180" />
            <el-table-column label="操作" width="300">
              <template #default="scope">
                <el-button
                  v-if="scope.row.role === 'MEMBER'"
                  type="primary" link class="role-grant"
                  @click="changeRole(scope.row, 'ADMIN')"
                >授予管理员</el-button>
                <el-button
                  v-if="scope.row.role === 'ADMIN'"
                  type="primary" link class="role-revoke"
                  @click="changeRole(scope.row, 'MEMBER')"
                >收回管理员</el-button>
                <el-button
                  v-if="scope.row.role !== 'OWNER'"
                  type="danger" link class="member-remove"
                  @click="openRemoval(scope.row)"
                >移除</el-button>
                <el-button
                  v-if="isOwner && scope.row.role !== 'OWNER'"
                  type="primary" link class="ownership-transfer"
                  @click="transfer(scope.row)"
                >转移所有权</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="members-pager"
            layout="prev, pager, next"
            :total="membersTotal"
            :page-size="pageSize"
            :current-page="membersPage"
            @current-change="changeMembersPage"
          />
        </el-card>

        <el-card shadow="never" class="admissions-card">
          <template #header>准入单</template>
          <div class="admissions-filter">
            <el-select v-model="admissionStatus" class="admission-status-filter" clearable placeholder="全部状态" @change="filterAdmissions">
              <el-option v-for="item in admissionStatusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </div>
          <el-table :data="admissions" class="admissions-table" :empty-text="ADMISSIONS_EMPTY_TIP">
            <el-table-column prop="id" label="准入单 id" width="100" />
            <el-table-column prop="subjectNo" label="主体编号" min-width="170" />
            <el-table-column label="形态" width="90">
              <template #default="scope">{{ ADMISSION_TYPE_LABELS[scope.row.type] ?? scope.row.type }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="scope">
                <el-tag :type="ADMISSION_STATUS_TYPES[scope.row.status] ?? 'info'">
                  {{ ADMISSION_STATUS_LABELS[scope.row.status] ?? scope.row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="operator" label="操作者" min-width="150" />
            <el-table-column prop="createdAt" label="时间" width="180" />
            <el-table-column label="理由" min-width="150">
              <template #default="scope">{{ scope.row.reason ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="scope">
                <el-button
                  v-if="scope.row.status === 'PENDING_APPROVAL'"
                  type="primary" link class="approval-open"
                  @click="openApproval(scope.row)"
                >审批</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="admissions-pager"
            layout="prev, pager, next"
            :total="admissionsTotal"
            :page-size="pageSize"
            :current-page="admissionsPage"
            @current-change="changeAdmissionsPage"
          />
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="策略" name="policies">
        <el-card shadow="never" class="policies-card">
          <template #header>有效策略</template>
          <el-table :data="policies" class="policies-table" :empty-text="EFFECTIVE_POLICIES_EMPTY_TIP">
            <el-table-column label="条目" min-width="140">
              <template #default="scope">{{ scope.row.displayName }}</template>
            </el-table-column>
            <el-table-column label="生效值" width="150">
              <template #default="scope">{{ policyValueLabel(scope.row.entryKey, scope.row.effectiveValue) }}</template>
            </el-table-column>
            <el-table-column label="来源" width="190">
              <template #default="scope">
                <el-tag :type="POLICY_PROVENANCE_TYPES[scope.row.provenance] ?? 'info'" class="provenance-tag">
                  {{ POLICY_PROVENANCE_LABELS[scope.row.provenance] ?? scope.row.provenance }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="平台值" width="140">
              <template #default="scope">{{ policyValueLabel(scope.row.entryKey, scope.row.platformValue) }}</template>
            </el-table-column>
            <el-table-column label="空间值" width="140">
              <template #default="scope">{{ policyValueLabel(scope.row.entryKey, scope.row.spaceValue) }}</template>
            </el-table-column>
            <el-table-column prop="note" label="说明" min-width="200" />
            <el-table-column label="红线" width="90">
              <template #default="scope">
                <el-tag v-if="scope.row.redline" type="danger" class="redline-tag">红线</el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" class="override-card">
          <template #header>提交空间覆盖</template>
          <div class="override-bar">
            <el-select v-model="overrideForm.entryKey" class="override-key">
              <el-option v-for="item in POLICY_CATALOG" :key="item.entryKey" :label="item.displayName" :value="item.entryKey" />
            </el-select>
            <el-select v-model="overrideForm.entryValue" class="override-value">
              <el-option v-for="item in overrideOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-button type="primary" class="override-submit" :disabled="policyBlocked" @click="submitOverride">提交覆盖</el-button>
            <span v-if="policyBlocked" class="policy-block-tip">{{ policyBlockTip }}</span>
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="configVisible" title="修改配置" width="520px" class="config-dialog">
      <p class="config-note">仅"简介 / 生效期"可变更，其余字段由服务端白名单校验。</p>
      <el-form label-width="100px">
        <el-form-item label="简介">
          <el-input v-model="configForm.intro" class="config-intro" type="textarea" :rows="3" maxlength="512" />
        </el-form-item>
        <el-form-item label="生效期起">
          <el-date-picker v-model="configForm.effectiveFrom" class="config-from" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="生效期止">
          <el-date-picker v-model="configForm.effectiveTo" class="config-to" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configVisible = false">取消</el-button>
        <el-button type="primary" class="config-submit" @click="submitConfig">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="inviteVisible" title="邀请成员" width="480px" class="invite-dialog">
      <el-form label-width="110px">
        <el-form-item label="被邀主体编号" required>
          <el-input v-model="inviteForm.subjectNo" class="invite-subject" placeholder="如 S20260925000001" />
        </el-form-item>
        <el-form-item label="理由">
          <el-input v-model="inviteForm.reason" class="invite-reason" maxlength="256" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inviteVisible = false">取消</el-button>
        <el-button type="primary" class="invite-submit" @click="submitInvite">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="removalVisible" title="移除成员" width="480px" class="removal-dialog">
      <el-form label-width="90px">
        <el-form-item label="理由" required>
          <el-input v-model="removalReason" class="removal-reason" maxlength="256" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="removalVisible = false">取消</el-button>
        <el-button type="danger" class="removal-submit" @click="submitRemoval">确认移除</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="approvalVisible" title="审批准入单" width="480px" class="approval-dialog">
      <el-form label-width="90px">
        <el-form-item label="结论">
          <el-radio-group v-model="approvalForm.decision" class="approval-decision">
            <el-radio value="APPROVE">通过</el-radio>
            <el-radio value="REJECT">拒绝</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="理由">
          <el-input v-model="approvalForm.reason" class="approval-reason" maxlength="256" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="approvalVisible = false">取消</el-button>
        <el-button type="primary" class="approval-submit" @click="submitApproval">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.detail-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.space-name {
  margin: 0;
  font-size: 18px;
}

.not-accessible-tip {
  margin-bottom: 12px;
}

.overview-card,
.lifecycle-card,
.logs-card,
.members-card,
.admissions-card,
.policies-card,
.override-card {
  margin-bottom: 16px;
}

.lifecycle-card :deep(.el-card__body),
.override-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.members-actions,
.admissions-filter {
  margin-bottom: 12px;
}

.logs-pager,
.members-pager,
.admissions-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.policy-block-tip {
  font-size: 13px;
  color: #b45309;
}
</style>
