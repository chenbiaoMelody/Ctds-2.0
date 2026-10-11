<template>
  <div class="detail-page">
    <el-alert v-if="notAccessible" :title="CONTRACT_NOT_ACCESSIBLE_TIP" type="error" :closable="false" />

    <template v-else-if="detail">
      <!-- A 总览：合约元数据 + 模板版本快照（C-4.1 S2-3 核对面）+ 定价快照 -->
      <el-descriptions title="合约总览" :column="3" border>
        <el-descriptions-item label="合约编号">{{ detail.contractNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="CONTRACT_STATUS_TAG_TYPES[detail.status] ?? 'info'">
            {{ CONTRACT_STATUS_LABELS[detail.status] ?? detail.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="当前条款版本">V{{ detail.currentClauseVersion }}</el-descriptions-item>
        <el-descriptions-item label="产品名称">{{ detail.productName }}</el-descriptions-item>
        <el-descriptions-item label="定价快照">{{ detail.pricingModel }} / {{ detail.priceAmount ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="模板引用">{{ detail.templateNo }}（版本快照 V{{ detail.templateVersionNo }}）</el-descriptions-item>
        <el-descriptions-item :label="MY_ROLE_LABELS.PROVIDER">{{ detail.providerSubjectNo }}</el-descriptions-item>
        <el-descriptions-item :label="MY_ROLE_LABELS.REQUESTER">{{ detail.requesterSubjectNo }}</el-descriptions-item>
        <el-descriptions-item label="内容哈希">{{ detail.contentHash }}</el-descriptions-item>
        <el-descriptions-item label="生效时间">{{ detail.effectiveAt ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="完结时间">{{ detail.endedAt ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="创建 / 更新">{{ detail.createdAt }} / {{ detail.updatedAt }}</el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="detail.terminationType"
        type="error"
        :closable="false"
        :title="`${TERMINATION_TYPE_LABELS[detail.terminationType] ?? detail.terminationType} — 终止理由：${detail.terminationReason ?? '—'}（操作者：${detail.terminatedBy ?? '—'}）`"
      />

      <!-- 协商动作条（按状态显隐；已生效无"单方撤回签署"入口——服务端强制） -->
      <div class="action-bar">
        <template v-if="detail.status === 'NEGOTIATING'">
          <el-button type="primary" @click="openPropose">提案</el-button>
          <el-button type="success" @click="confirmClausesAction">确认</el-button>
          <el-button type="danger" plain @click="confirmTerminate('terminateNegotiation')">终止协商</el-button>
        </template>
        <template v-else-if="detail.status === 'PENDING_SIGNATURE' || detail.status === 'PARTIALLY_SIGNED'">
          <el-button type="primary" @click="openSign">签署</el-button>
          <el-button type="danger" plain @click="confirmTerminate('refuseSignature')">拒签终止</el-button>
        </template>
        <template v-else-if="detail.status === 'EFFECTIVE'">
          <el-button type="warning" plain @click="confirmRelease">合意解除</el-button>
        </template>
      </div>

      <!-- B 当前条款全文 + F 策略视图 -->
      <el-card shadow="never">
        <template #header>当前条款全文（V{{ detail.currentClauseVersion }}）</template>
        <el-descriptions :column="1" border>
          <el-descriptions-item v-for="(value, key) in detail.clauseValues.slots" :key="key" :label="slotName(String(key))">
            {{ value }}
          </el-descriptions-item>
        </el-descriptions>
        <h4>使用控制策略（五要素全文）</h4>
        <el-descriptions :column="2" border>
          <el-descriptions-item :label="POLICY_ELEMENT_LABELS.quota">
            {{ strategyLine('quota') }}
          </el-descriptions-item>
          <el-descriptions-item :label="POLICY_ELEMENT_LABELS.term">
            {{ strategyLine('term') }}
          </el-descriptions-item>
          <el-descriptions-item label="用途">
            {{ strategyLine('purpose') }}
          </el-descriptions-item>
          <el-descriptions-item label="域内范围">
            {{ strategyLine('territory') }}
          </el-descriptions-item>
          <el-descriptions-item :label="POLICY_ELEMENT_LABELS.noRedistribution">
            {{ detail.clauseValues.strategy.noRedistribution?.enabled ? '是' : '未启用' }}
          </el-descriptions-item>
          <el-descriptions-item label="无使用限制声明">
            {{ detail.clauseValues.strategy.noRestrictionDeclared ? '是（显式声明）' : '未声明' }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- C 条款版本链（R8） -->
      <el-card shadow="never">
        <template #header>条款版本链</template>
        <el-table :data="clauseVersions" :empty-text="'（仅初始版本）'">
          <el-table-column label="版本号" width="80">
            <template #default="scope">V{{ scope.row.versionNo }}</template>
          </el-table-column>
          <el-table-column prop="proposedBy" label="提案方" width="170">
            <template #default="scope">{{ scope.row.proposedBy ?? '发起' }}</template>
          </el-table-column>
          <el-table-column prop="proposedAt" label="提案时间" width="180">
            <template #default="scope">{{ scope.row.proposedAt ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="双方确认时间" min-width="240">
            <template #default="scope">
              {{ MY_ROLE_LABELS.PROVIDER }}：{{ scope.row.confirmedProviderAt ?? '—' }} / {{ MY_ROLE_LABELS.REQUESTER }}：{{ scope.row.confirmedRequesterAt ?? '—' }}
            </template>
          </el-table-column>
          <el-table-column label="变更明细" min-width="200">
            <template #default="scope">
              <div v-for="change in scope.row.changes ?? []" :key="change.slot">
                {{ change.slot }}：{{ change.from ?? '—' }} → {{ change.to ?? '—' }}
              </div>
              <span v-if="!scope.row.changes?.length">—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- E 签署与存证区（R7 + R9） -->
      <el-card shadow="never">
        <template #header>签署与存证</template>
        <el-table :data="detail.signatures" :empty-text="'暂无签署记录'">
          <el-table-column label="角色" width="100">
            <template #default="scope">{{ MY_ROLE_LABELS[scope.row.partyRole] ?? scope.row.partyRole }}</template>
          </el-table-column>
          <el-table-column prop="subjectNo" label="主体编号" min-width="170" />
          <el-table-column prop="did" label="DID" min-width="200" />
          <el-table-column prop="signedAt" label="签署时间" width="180">
            <template #default="scope">{{ scope.row.signedAt ?? '—' }}</template>
          </el-table-column>
        </el-table>
        <div v-if="detail.attestation" class="attestation">
          存证摘要：内容哈希 {{ detail.attestation.contentHash }}；{{ MY_ROLE_LABELS.PROVIDER }} {{ detail.attestation.providerDid }}
          （{{ detail.attestation.providerSignedAt }}）；{{ MY_ROLE_LABELS.REQUESTER }} {{ detail.attestation.requesterDid }}
          （{{ detail.attestation.requesterSignedAt }}）；存证时间 {{ detail.attestation.attestedAt }}
        </div>
        <div class="verify-row">
          <el-button @click="runVerify">验签核验</el-button>
        </div>
        <el-table v-if="verifyReport" :data="verifyReport.results" size="small">
          <el-table-column label="角色" width="100">
            <template #default="scope">{{ MY_ROLE_LABELS[scope.row.partyRole] ?? scope.row.partyRole }}</template>
          </el-table-column>
          <el-table-column prop="did" label="DID" min-width="200" />
          <el-table-column prop="result" label="结果" width="120" />
          <el-table-column label="原因" min-width="140">
            <template #default="scope">{{ scope.row.reason ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="verifiedAt" label="核验时间" width="180" />
        </el-table>
      </el-card>

      <!-- G 策略与使用区（3.4.6 移交-1 承载；诚实边界两则常驻） -->
      <el-card shadow="never">
        <template #header>策略与使用</template>
        <div class="usage-actions">
          <el-button @click="openSimulate">模拟试算</el-button>
          <el-button @click="testbenchVisible = true">测试台</el-button>
          <el-button v-if="isParticipant" type="primary" @click="openExecute">发起使用</el-button>
        </div>
        <el-alert :title="SIMULATION_HONESTY_TIP" type="info" :closable="false" />
        <el-alert :title="EXECUTION_DEMO_TIP" type="warning" :closable="false" />

        <h4>使用摘要（R12）</h4>
        <template v-if="summary">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="配额（已用/上限）">
              {{ summary.quota ? `${summary.quota.used} / ${summary.quota.limit}` : '无配额上限' }}
            </el-descriptions-item>
            <el-descriptions-item label="放行次数">{{ summary.allowedCount }}</el-descriptions-item>
            <el-descriptions-item label="拒绝次数">{{ summary.deniedCount }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="summary.records.list" size="small" :empty-text="'暂无执行记录'">
            <el-table-column prop="requesterNo" label="操作者" min-width="160" />
            <el-table-column prop="actionType" label="动作" width="120" />
            <el-table-column label="结果" width="100">
              <template #default="scope">
                <el-tag :type="scope.row.outcome === 'ALLOWED' ? 'success' : 'danger'">
                  {{ scope.row.outcome === 'ALLOWED' ? '放行' : '拒绝' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="触发要素" min-width="180">
              <template #default="scope">{{ scope.row.violations ?? '—' }}</template>
            </el-table-column>
            <el-table-column prop="usedCount" label="已用计数" width="90" />
            <el-table-column prop="occurredAt" label="时间" width="180" />
          </el-table>
          <el-pagination
            v-model:current-page="summaryPage"
            layout="prev, pager, next"
            :total="summary.records.total"
            :page-size="summaryPageSize"
            @current-change="loadSummary"
          />
        </template>
      </el-card>

      <!-- H 合约留痕区（R13；Q2-A） -->
      <el-card shadow="never">
        <template #header>合约留痕</template>
        <el-table v-loading="logsLoading" :data="actionLogs" size="small" :empty-text="'暂无记录'">
          <el-table-column label="动作" width="120">
            <template #default="scope">{{ ACTION_LOG_ACTION_LABELS[scope.row.action] ?? scope.row.action }}</template>
          </el-table-column>
          <el-table-column label="版本号" width="80">
            <template #default="scope">{{ scope.row.versionNo ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="actorSubjectNo" label="操作者主体编号" min-width="170" />
          <el-table-column prop="createdAt" label="时间" width="180" />
          <el-table-column label="从何值 → 到何值" min-width="180">
            <template #default="scope">{{ scope.row.fromValue ?? '—' }} → {{ scope.row.toValue ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="拒绝码" width="90">
            <template #default="scope">{{ scope.row.reasonCode ?? '—' }}</template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="logsPage"
          layout="prev, pager, next"
          :total="logsTotal"
          :page-size="logsPageSize"
          @current-change="loadLogs"
        />
      </el-card>
    </template>

    <!-- 提案弹窗（W6）：全量条款编辑（值可改 + 增删槽位行）+ 策略编辑器 -->
    <el-dialog v-model="proposeVisible" title="提交提案" width="680px">
      <div class="propose-form" v-if="detail">
        <ClauseEditor mode="values" v-model="proposeSlots" />
        <StrategyEditor v-model="proposeStrategy" />
      </div>
      <template #footer>
        <el-button @click="proposeVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPropose">确定</el-button>
      </template>
    </el-dialog>

    <!-- 签署弹窗（W9）：DID 必填；取值路径提示 -->
    <el-dialog v-model="signVisible" title="签署合约" width="480px">
      <div class="sign-form">
        <el-input v-model="signDid" placeholder="DID（签署人标识，如 did:ctds:...）" />
        <div class="sign-hint">可在『DID 管理』页查询双方 DID</div>
      </div>
      <template #footer>
        <el-button @click="signVisible = false">取消</el-button>
        <el-button type="primary" @click="submitSign">确定</el-button>
      </template>
    </el-dialog>

    <!-- 模拟试算弹窗（假想上下文 + 可选策略草稿；拒绝以数据展示） -->
    <el-dialog v-model="simulateVisible" title="模拟试算" width="640px">
      <div class="simulate-form">
        <el-select v-model="simulateActionType" class="simulate-select">
          <el-option label="使用" value="USE" />
          <el-option label="再分发" value="REDISTRIBUTE" />
        </el-select>
        <el-input v-model="simulatePurpose" placeholder="用途（可选）" :maxlength="200" />
        <el-input v-model="simulateTerritory" placeholder="地域（可选）" :maxlength="200" />
        <el-input v-model="simulateUsedCount" type="number" placeholder="假想已用次数（可选；缺省 = 真实计数）" />
        <el-date-picker v-model="simulateDate" type="date" value-format="YYYY-MM-DD" placeholder="假想日期（可选；缺省 = 今天）" />
        <el-checkbox v-model="useDraft">使用策略草稿（不勾选 = 按当前合约策略）</el-checkbox>
        <StrategyEditor v-if="useDraft" v-model="draftStrategy" />
      </div>
      <template #footer>
        <el-button @click="simulateVisible = false">取消</el-button>
        <el-button type="primary" @click="submitSimulate">开始试算</el-button>
      </template>
    </el-dialog>

    <!-- 发起使用弹窗（受控执行；仅参与方；无幂等——每次提交计一次使用） -->
    <el-dialog v-model="executeVisible" title="发起使用（受控执行）" width="480px">
      <div class="execute-form">
        <el-select v-model="executeActionType">
          <el-option label="使用" value="USE" />
          <el-option label="再分发" value="REDISTRIBUTE" />
        </el-select>
        <el-input v-model="executePurpose" placeholder="用途（必填）" :maxlength="200" />
        <el-input v-model="executeTerritory" placeholder="地域（必填）" :maxlength="200" />
        <div class="execute-hint">每次提交计一次使用（受控执行无幂等键，重复提交 = 新一次调用）</div>
      </div>
      <template #footer>
        <el-button @click="executeVisible = false">取消</el-button>
        <el-button type="primary" @click="submitExecute">确定</el-button>
      </template>
    </el-dialog>

    <!-- 测试台报告（一键运行；零落库） -->
    <el-drawer v-model="testbenchVisible" title="策略测试台" size="640px">
      <div class="testbench-body">
        <el-button type="primary" @click="runTestbench">一键运行</el-button>
        <template v-if="testbench">
          <el-descriptions :column="4" border size="small" class="testbench-summary">
            <el-descriptions-item label="总数">{{ testbench.summary.total }}</el-descriptions-item>
            <el-descriptions-item label="PASS">{{ testbench.summary.pass }}</el-descriptions-item>
            <el-descriptions-item label="FAIL">{{ testbench.summary.fail }}</el-descriptions-item>
            <el-descriptions-item label="SKIPPED">{{ testbench.summary.skipped }}</el-descriptions-item>
          </el-descriptions>
          <div class="testbench-runat">运行时间：{{ testbench.runAt }}</div>
          <el-table :data="testbench.scenarios" size="small">
            <el-table-column prop="code" label="编号" width="70" />
            <el-table-column prop="elementKey" label="要素" min-width="120" />
            <el-table-column prop="direction" label="方向" width="90" />
            <el-table-column prop="expectation" label="期望" width="110" />
            <el-table-column label="结果" width="100">
              <template #default="scope">
                <el-tag :type="scope.row.outcome === 'PASS' ? 'success' : scope.row.outcome === 'FAIL' ? 'danger' : 'info'">
                  {{ scope.row.outcome }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="130">
              <template #default="scope">{{ scope.row.note ?? '—' }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/**
 * 合约详情（WBS-3.4.7 hifi §6.4；深链 /contracts/workbench/:contractNo）：总览（含模板版本
 * 快照——C-4.1 S2-3 核对面）+ 条款全文与策略视图（五要素）+ 条款版本链（R8）+ 协商动作条
 * （按状态显隐；已生效无"单方撤回"入口）+ 签署与存证（R7/R9）+ 策略与使用区（模拟试算 /
 * 测试台 / 受控执行〔仅参与方〕/ R12 使用摘要 + 诚实边界两则常驻）+ 合约留痕区（R13）。
 * 动作结果一律以响应刷新（无乐观更新）；错误文案原样展示（不吞不改写）；模拟拒绝 = 数据
 * 展示不弹错误（ADR-021 §2.2 语义）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getContractDetail,
  listClauseVersions,
  proposeClauses,
  confirmClauses,
  terminateNegotiation,
  signContract,
  refuseSignature,
  releaseConsent,
  verifySignatures,
  getUsageSummary,
  simulatePolicy,
  runPolicyTestbench,
  executeUsage,
  listContractActionLogs,
  getTemplate,
  type ContractDetail,
  type ClauseVersionItem,
  type VerificationReport,
  type UsageSummary,
  type SimulationResult,
  type TestbenchReport,
  type ExecutionResult,
  type ContractActionLogItem,
  type ClauseFramework,
} from '../../api/contract'
import { getDemoSubject } from '../../api/client'
import {
  POLICY_ELEMENT_LABELS,
  CONTRACT_STATUS_LABELS,
  CONTRACT_STATUS_TAG_TYPES,
  MY_ROLE_LABELS,
  TERMINATION_TYPE_LABELS,
  ACTION_LOG_ACTION_LABELS,
  CONTRACT_NOT_ACCESSIBLE_TIP,
  SIMULATION_HONESTY_TIP,
  EXECUTION_DEMO_TIP,
} from '../../constants/contract'
import ClauseEditor from './components/ClauseEditor.vue'
import StrategyEditor from './components/StrategyEditor.vue'

const route = useRoute()
const contractNo = String(route.params.contractNo ?? '')

const notAccessible = ref(false)
const detail = ref<ContractDetail | null>(null)
const clauseVersions = ref<ClauseVersionItem[]>([])
const framework = ref<ClauseFramework | null>(null)

const isParticipant = computed(() => {
  if (!detail.value) return false
  const subject = getDemoSubject()
  return subject === detail.value.providerSubjectNo || subject === detail.value.requesterSubjectNo
})

function slotName(key: string): string {
  const slot = framework.value?.slots.find((item) => item.key === key)
  return slot ? `${slot.name}（${key}）` : key
}

function strategyLine(element: 'quota' | 'term' | 'purpose' | 'territory'): string {
  const el = detail.value?.clauseValues.strategy[element]
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

async function loadAll(): Promise<void> {
  try {
    detail.value = await getContractDetail(contractNo)
    notAccessible.value = false
  } catch (err) {
    notAccessible.value = true
    ElMessage.error((err as Error).message)
    return
  }
  try {
    clauseVersions.value = await listClauseVersions(contractNo)
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
  try {
    const tpl = await getTemplate(detail.value.templateNo)
    framework.value = tpl.clauseFramework
  } catch {
    // 模板不可达不影响详情主面（键名直显）
  }
  await loadSummary()
  await loadLogs()
}

// ==== 协商动作（W6/W7/W8） ====

const proposeVisible = ref(false)
const proposeSlots = ref<{ slots: Record<string, string> }>({ slots: {} })
const proposeStrategy = ref<Record<string, unknown>>({})

function openPropose(): void {
  if (!detail.value) return
  proposeSlots.value = { slots: { ...detail.value.clauseValues.slots } }
  proposeStrategy.value = { ...detail.value.clauseValues.strategy } as Record<string, unknown>
  proposeVisible.value = true
}

async function submitPropose(): Promise<void> {
  try {
    const result = await proposeClauses(contractNo, {
      clauseValues: {
        slots: proposeSlots.value.slots,
        strategy: proposeStrategy.value as never,
      },
    })
    ElMessage.success(`提案成功：条款版本 V${result.clauseVersionNo}（原 V${result.previousVersionNo}）`)
    proposeVisible.value = false
    await loadAll()
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
}

async function confirmClausesAction(): Promise<void> {
  try {
    await confirmClauses(contractNo)
    ElMessage.success('确认成功')
    await loadAll()
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
}

async function confirmTerminate(action: 'terminateNegotiation' | 'refuseSignature'): Promise<void> {
  try {
    await ElMessageBox.confirm('终止后不可恢复，确认终止？', '二次确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' })
  } catch {
    return // 取消 = 零请求
  }
  try {
    if (action === 'terminateNegotiation') {
      await terminateNegotiation(contractNo)
    } else {
      await refuseSignature(contractNo)
    }
    ElMessage.success(CONTRACT_STATUS_LABELS.TERMINATED)
    await loadAll()
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
}

async function confirmRelease(): Promise<void> {
  try {
    await ElMessageBox.confirm('合意解除须双方各自确认，确认？', '二次确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' })
  } catch {
    return
  }
  try {
    const result = await releaseConsent(contractNo)
    if (result.status === 'COMPLETED') {
      ElMessage.success(`双方已确认，合约${CONTRACT_STATUS_LABELS.COMPLETED}`)
    } else {
      ElMessage.success('已确认，等待对方确认')
    }
    await loadAll()
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
}

// ==== 签署与验签（W9/W10 + R9） ====

const signVisible = ref(false)
const signDid = ref('')

function openSign(): void {
  signDid.value = ''
  signVisible.value = true
}

async function submitSign(): Promise<void> {
  // DID 必填前置（零请求——hifi §8-1）
  if (!signDid.value.trim()) {
    return
  }
  try {
    const result = await signContract(contractNo, { did: signDid.value.trim() })
    ElMessage.success(
      result.status === 'EFFECTIVE'
        ? `双方签署齐备，合约${CONTRACT_STATUS_LABELS.EFFECTIVE}`
        : '签署成功（等待对方签署）',
    )
    signVisible.value = false
    await loadAll()
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
}

const verifyReport = ref<VerificationReport | null>(null)

async function runVerify(): Promise<void> {
  try {
    verifyReport.value = await verifySignatures(contractNo)
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
}

// ==== 策略与使用（模拟/测试台/受控执行/R12） ====

const summaryPage = ref(1)
const summaryPageSize = 10
const summary = ref<UsageSummary | null>(null)

async function loadSummary(): Promise<void> {
  try {
    summary.value = await getUsageSummary({
      contractNo,
      pageNum: summaryPage.value,
      pageSize: summaryPageSize,
    })
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
}

const simulateVisible = ref(false)
const simulateActionType = ref('USE')
const simulatePurpose = ref('')
const simulateTerritory = ref('')
const simulateUsedCount = ref('')
const simulateDate = ref<string | null>(null)
const useDraft = ref(false)
const draftStrategy = ref<Record<string, unknown>>({})
const simulation = ref<SimulationResult | null>(null)

function openSimulate(): void {
  simulation.value = null
  simulatePurpose.value = ''
  simulateTerritory.value = ''
  simulateUsedCount.value = ''
  simulateDate.value = null
  useDraft.value = false
  draftStrategy.value = {}
  simulateVisible.value = true
}

async function submitSimulate(): Promise<void> {
  try {
    // 模拟拒绝是数据（HTTP 200 + allowed=false）——不弹错误，以结果面板展示
    simulation.value = await simulatePolicy(contractNo, {
      actionType: simulateActionType.value,
      purpose: simulatePurpose.value.trim() || undefined,
      territory: simulateTerritory.value.trim() || undefined,
      assumedUsedCount: simulateUsedCount.value.trim() === '' ? undefined : Number(simulateUsedCount.value),
      assumedDate: simulateDate.value ?? undefined,
      strategyDocument: useDraft.value ? (draftStrategy.value as never) : undefined,
    })
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
}

const testbenchVisible = ref(false)
const testbench = ref<TestbenchReport | null>(null)

async function runTestbench(): Promise<void> {
  try {
    testbench.value = await runPolicyTestbench(contractNo)
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
}

const executeVisible = ref(false)
const executeActionType = ref('USE')
const executePurpose = ref('')
const executeTerritory = ref('')

function openExecute(): void {
  executeActionType.value = 'USE'
  executePurpose.value = ''
  executeTerritory.value = ''
  executeVisible.value = true
}

async function submitExecute(): Promise<void> {
  // 必填前置（零请求）：用途与地域必填
  if (!executePurpose.value.trim() || !executeTerritory.value.trim()) {
    return
  }
  try {
    const result: ExecutionResult = await executeUsage(contractNo, {
      actionType: executeActionType.value,
      purpose: executePurpose.value.trim(),
      territory: executeTerritory.value.trim(),
    })
    ElMessage.success(`使用已放行，本次计数后已用 ${result.usedCount} 次`)
    executeVisible.value = false
    await loadSummary()
    await loadLogs()
  } catch (err) {
    // 越界 1008C0020 / 状态失效 1008C0013 原样展示 + 刷新摘要（拒绝行出现）
    ElMessage.error((err as Error).message)
    await loadSummary()
    await loadLogs()
  }
}

// ==== 合约留痕（R13） ====

const actionLogs = ref<ContractActionLogItem[]>([])
const logsPage = ref(1)
const logsTotal = ref(0)
const logsPageSize = 10
const logsLoading = ref(false)

async function loadLogs(): Promise<void> {
  logsLoading.value = true
  try {
    const result = await listContractActionLogs({
      contractNo,
      pageNum: logsPage.value,
      pageSize: logsPageSize,
    })
    actionLogs.value = result.list
    logsTotal.value = result.total
  } catch (err) {
    ElMessage.error((err as Error).message)
  } finally {
    logsLoading.value = false
  }
}

onMounted(() => {
  void loadAll()
})
</script>

<style scoped>
.detail-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.action-bar {
  display: flex;
  gap: 8px;
}

.attestation {
  margin-top: 12px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.verify-row {
  margin: 12px 0;
}

.usage-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.propose-form,
.sign-form,
.simulate-form,
.execute-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sign-hint,
.execute-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.simulate-select {
  max-width: 200px;
}

.testbench-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.testbench-runat {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
