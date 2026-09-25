<script setup lang="ts">
/**
 * WBS-3.1.11 DID 演示与验证（规格 C-1.2 §6 第 6 条边界 + 行为 2/行为 3）：
 * 四区 = 演示代签 / 验证（三查结论与原因）/ 解析（公开要素，"未登记"以业务答复样式）/ 验证留痕。
 * 演示签名入口仅演示/调试期可用（生产默认关闭；关闭时以提示样式说明，不冒充操作失败）；
 * 系统态"不可用"与业务态"不通过"分开展示；界面只展示公开要素与留痕四要素，不出现任何私钥与数据原文。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../api/client'
import {
  demoSign,
  fetchVerificationLogs,
  resolveDid,
  verifySignature,
  type DemoSignatureView,
  type ResolutionView,
  type VerificationLogView,
  type VerificationView,
} from '../../api/did'
import {
  DEMO_DISABLED_HINT,
  RESOLVE_NOT_REGISTERED_TEXT,
  VERIFICATION_REASON_LABELS,
  VERIFICATION_RESULT_LABELS,
  labelOf,
} from '../../constants/did'

const route = useRoute()
const router = useRouter()

const demoDid = ref(String(route.query.did ?? ''))
const demoText = ref('')
const signing = ref(false)
const signResult = ref<DemoSignatureView | null>(null)
const demoDisabled = ref(false)
const demoNotRegistered = ref('')

const verifyDataInput = ref('')
const verifySignatureInput = ref('')
const verifying = ref(false)
const verifyResult = ref<VerificationView | null>(null)

const resolveDidInput = ref('')
const resolving = ref(false)
const resolveResult = ref<ResolutionView | null>(null)
const resolveMessage = ref('')

const logs = ref<VerificationLogView[]>([])
const logLoading = ref(false)
const logTotal = ref(0)
const logPageNum = ref(1)
const logPageSize = ref(10)

const verifyAlertType = computed(() => {
  if (!verifyResult.value) {
    return 'info'
  }
  if (verifyResult.value.result === 'PASS') {
    return 'success'
  }
  return verifyResult.value.result === 'UNAVAILABLE' ? 'warning' : 'error'
})

const verifySummary = computed(() => {
  if (!verifyResult.value) {
    return ''
  }
  const result = labelOf(VERIFICATION_RESULT_LABELS, verifyResult.value.result)
  const reason = verifyResult.value.reason
  return reason
    ? `验证结论：${result}；原因：${labelOf(VERIFICATION_REASON_LABELS, reason)}`
    : `验证结论：${result}`
})

async function loadLogs(): Promise<void> {
  logLoading.value = true
  try {
    const page = await fetchVerificationLogs({
      did: '',
      pageNum: logPageNum.value,
      pageSize: logPageSize.value,
    })
    logs.value = page.list
    logTotal.value = page.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '验证留痕加载失败，请稍后重试')
  } finally {
    logLoading.value = false
  }
}

function changeLogPage(page: number): void {
  logPageNum.value = page
  void loadLogs()
}

/** 演示代签：结果自动填入验证区（承载剧本 S3 步骤 5"用留存签名复核"）。 */
async function submitDemoSign(): Promise<void> {
  demoDisabled.value = false
  demoNotRegistered.value = ''
  signing.value = true
  try {
    const result = await demoSign(demoDid.value.trim(), demoText.value)
    signResult.value = result
    verifyDataInput.value = result.data
    verifySignatureInput.value = result.signature
    ElMessage.success('演示签名已生成，并已自动填入下方验证区')
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === '1000C0003') {
        // 入口未启用（生产默认态）：环境状态，不是操作失败
        demoDisabled.value = true
        return
      }
      ElMessage.error(error.message)
      return
    }
    ElMessage.error('操作失败，请稍后重试')
  } finally {
    signing.value = false
  }
}

async function submitVerify(): Promise<void> {
  verifying.value = true
  try {
    verifyResult.value = await verifySignature(
      demoDid.value.trim(),
      verifyDataInput.value.trim(),
      verifySignatureInput.value.trim(),
    )
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '验证失败，请稍后重试')
  } finally {
    verifying.value = false
  }
}

/** 解析：未登记 DID 属业务答复（warning），不作系统报错样式。 */
async function submitResolve(): Promise<void> {
  resolving.value = true
  resolveMessage.value = ''
  resolveResult.value = null
  try {
    resolveResult.value = await resolveDid(resolveDidInput.value.trim())
  } catch (error) {
    if (error instanceof ApiError) {
      resolveMessage.value = error.code === '1005B0003' ? RESOLVE_NOT_REGISTERED_TEXT : error.message
      return
    }
    resolveMessage.value = '解析失败，请稍后重试'
  } finally {
    resolving.value = false
  }
}

function backToManagement(): void {
  void router.push('/did')
}

onMounted(() => {
  void loadLogs()
})
</script>

<template>
  <div class="page">
    <el-page-header class="back" content="DID 演示与验证" @back="backToManagement" />
    <el-alert
      class="page-alert"
      type="info"
      :closable="false"
      show-icon
      title="仅演示/调试期使用：平台侧演示签名入口在生产环境默认关闭（规格 §6.6）"
    />

    <el-card shadow="never" class="block">
      <template #header>演示代签（运营管理员代主体 DID 生成签名）</template>
      <el-input v-model="demoDid" class="demo-did-input" placeholder="DID 标识，如 did:ctds:S20260925000001.1" />
      <el-input
        v-model="demoText"
        class="demo-text-input"
        type="textarea"
        :rows="3"
        placeholder="待签文本，如：蓝天数据科技有限公司确认接入城市可信数据空间"
      />
      <p class="hint">仅演示/调试期使用：真实业务签名由主体侧发起，生产环境该入口默认关闭。</p>
      <el-button type="primary" class="demo-sign-btn" :loading="signing" @click="submitDemoSign">
        生成演示签名
      </el-button>

      <el-alert
        v-if="demoDisabled"
        class="demo-alert"
        type="warning"
        :closable="false"
        :title="DEMO_DISABLED_HINT"
      />

      <template v-if="signResult">
        <p><span class="label">原文（Base64）</span></p>
        <el-input class="demo-data-result" :model-value="signResult.data" readonly />
        <p><span class="label">签名（SM2 DER Base64）</span></p>
        <el-input class="demo-signature-result" :model-value="signResult.signature" readonly />
        <p class="hint">已自动填入下方验证区（共 {{ signResult.signedAt }} 生成）。</p>
      </template>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>验证（签名 / 状态 / 主体绑定 三查）</template>
      <el-input v-model="verifyDataInput" class="verify-data" placeholder="原文（Base64）" />
      <el-input v-model="verifySignatureInput" class="verify-signature" placeholder="签名（Base64）" />
      <el-button type="primary" class="verify-btn" :loading="verifying" @click="submitVerify">验证</el-button>
      <div v-if="verifyResult" class="verify-result">
        <el-alert
          class="verify-alert"
          :type="verifyAlertType"
          :closable="false"
          :title="verifySummary"
        />
        <p class="hint">验证时间：{{ verifyResult.verifiedAt }}</p>
      </div>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>解析（DID 文档公开要素与状态）</template>
      <el-input v-model="resolveDidInput" class="resolve-did" placeholder="DID 标识" />
      <el-button type="primary" class="resolve-btn" :loading="resolving" @click="submitResolve">解析</el-button>
      <el-alert
        v-if="resolveMessage"
        class="resolve-alert"
        type="warning"
        :closable="false"
        :title="resolveMessage"
      />
      <div v-if="resolveResult" class="resolve-result">
        <p>状态：{{ resolveResult.status === 'ACTIVE' ? '有效' : '已吊销' }}</p>
        <p>公钥值：{{ resolveResult.document.publicKey?.valueHex ?? '—' }}</p>
        <p>控制者（主体编号）：{{ resolveResult.document.controller ?? '—' }}</p>
        <p>创建时间：{{ resolveResult.document.created ?? '—' }}</p>
      </div>
      <p class="hint">仅公开要素，界面不展示任何私钥。</p>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>验证留痕</template>
      <p class="hint">留痕仅含时间/DID/结果/原因，不保存任何业务数据原文。</p>
      <el-table :data="logs" v-loading="logLoading" empty-text="暂无验证留痕">
        <el-table-column prop="occurredAt" label="时间" width="180" />
        <el-table-column prop="did" label="DID 标识" min-width="240" />
        <el-table-column label="结果" width="160">
          <template #default="scope">{{ labelOf(VERIFICATION_RESULT_LABELS, scope.row.result) }}</template>
        </el-table-column>
        <el-table-column label="原因" min-width="180">
          <template #default="scope">{{ labelOf(VERIFICATION_REASON_LABELS, scope.row.reason) }}</template>
        </el-table-column>
      </el-table>
      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :total="logTotal"
        :page-size="logPageSize"
        :current-page="logPageNum"
        @current-change="changeLogPage"
      />
      <el-button class="log-refresh-btn" @click="loadLogs">刷新</el-button>
    </el-card>
  </div>
</template>

<style scoped>
.back {
  margin-bottom: 12px;
}

.page-alert {
  margin-bottom: 12px;
}

.block {
  margin-bottom: 12px;
}

.hint {
  margin: 8px 0;
  font-size: 13px;
  color: #6b7280;
}

.label {
  color: #6b7280;
  font-size: 13px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
