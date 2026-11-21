<script setup lang="ts">
/**
 * WBS-3.10.4 页面：AI 服务配置（hifi §5 / lofi W4；C-11.1A B1~B4）：
 * - 四区块单页：网关总控（开关/预算/默认供应商）+ 判定预览（三态芯片，复用后端判定语义）
 *   + 供应商连接参数（封闭清单四行，行内编辑地址/模型名；密钥列固定灰字零输入，Q4-A）
 *   + 变更留痕（分页五列只读）；
 * - 保存 = POST /save 全量载荷（前置校验零请求拒绝；后端 1014C0001 权威，错误原文呈现）；
 * - 诚实边界常驻提示条：V1.0 配置即预留，无运行时消费方（规格 B4-3）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../api/client'
import {
  fetchAiConfig,
  fetchAiConfigChanges,
  fetchAiConfigPreview,
  saveAiConfig,
  type AiConfigChangeLogView,
  type AiConfigPreviewView,
  type AiConfigSaveRequest,
  type AiConfigView,
} from '../../api/ai'
import {
  AI_CONFIG_HONESTY_NOTICE,
  AI_KEY_NOTE,
  AI_PREVIEW_NOTE,
  decisionLabelOf,
  decisionTagTypeOf,
  validateAiConfigForm,
} from '../../constants/ai'
import { guideToLoginIfAuthFailed } from '../../api/authGuide'
import { useRouter } from 'vue-router'

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const serverView = ref<AiConfigView | null>(null)
const form = ref<AiConfigSaveRequest>({ enabled: false, defaultProvider: 'glm', tokenBudget: 0, providers: [] })
const preview = ref<AiConfigPreviewView | null>(null)
const changes = ref<AiConfigChangeLogView[]>([])
const changesTotal = ref(0)
const changesPageNum = ref(1)
const changesPageSize = ref(10)
const saveError = ref<string | null>(null)

/** 供应商行视图（表内行内编辑；显示名/默认徽标以后端视图为准）。 */
const providerRows = computed(() => serverView.value?.providers ?? [])

async function load(): Promise<void> {
  loading.value = true
  try {
    const [view, previewView] = await Promise.all([fetchAiConfig(), fetchAiConfigPreview()])
    serverView.value = view
    form.value = {
      enabled: view.enabled,
      defaultProvider: view.defaultProvider,
      tokenBudget: view.tokenBudget,
      providers: view.providers.map((row) => ({
        providerId: row.providerId,
        baseUrl: row.baseUrl,
        model: row.model,
      })),
    }
    preview.value = previewView
    saveError.value = null
    await loadChanges()
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    saveError.value = error instanceof ApiError ? error.message : 'AI 服务配置加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function loadChanges(): Promise<void> {
  try {
    const page = await fetchAiConfigChanges(changesPageNum.value, changesPageSize.value)
    changes.value = page.list
    changesTotal.value = page.total
  } catch {
    changes.value = []
    changesTotal.value = 0
  }
}

async function refreshPreview(): Promise<void> {
  try {
    preview.value = await fetchAiConfigPreview()
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    ElMessage.error(error instanceof ApiError ? error.message : '判定预览失败，请稍后重试')
  }
}

function discard(): void {
  if (serverView.value) {
    form.value = {
      enabled: serverView.value.enabled,
      defaultProvider: serverView.value.defaultProvider,
      tokenBudget: serverView.value.tokenBudget,
      providers: serverView.value.providers.map((row) => ({
        providerId: row.providerId,
        baseUrl: row.baseUrl,
        model: row.model,
      })),
    }
    saveError.value = null
  }
}

/** 保存（唯一写入口）：前置校验零请求拒绝 → 全量 POST → 成功刷新视图与留痕；失败原文呈现。 */
async function submitSave(): Promise<void> {
  const violation = validateAiConfigForm(form.value)
  if (violation) {
    ElMessage.error(violation)
    return
  }
  saving.value = true
  try {
    const saved = await saveAiConfig(form.value)
    serverView.value = saved
    ElMessage.success('AI 服务配置已保存')
    saveError.value = null
    await Promise.all([load(), refreshPreview()])
  } catch (error) {
    if (guideToLoginIfAuthFailed(error, router)) return
    saveError.value = error instanceof ApiError
      ? `${error.code} ${error.message}`
      : '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

function changePage(page: number): void {
  changesPageNum.value = page
  void loadChanges()
}

function valueLabel(value: string | null): string {
  return value === null || value === '' ? '（空）' : value
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <h2 class="page-title">AI 服务配置</h2>
    <p class="page-desc">模型网关的开关、token 预算与供应商连接参数管理（V1.0 配置即预留）。</p>

    <el-alert class="ai-config-notice" type="warning" :closable="false" :title="AI_CONFIG_HONESTY_NOTICE" />

    <el-alert
      v-if="saveError"
      class="ai-config-error"
      type="error"
      :closable="false"
      :title="saveError"
    />

    <el-card shadow="never" class="ai-config-block">
      <template #header><span class="block-title">① 网关总控</span></template>
      <div class="control-row">
        <span class="control-label">网关总开关</span>
        <el-switch v-model="form.enabled" class="ai-config-enabled" />
        <span class="control-hint">关闭（缺省；V1.0 零消耗安全默认）</span>
      </div>
      <div class="control-row">
        <span class="control-label">token 预算上限</span>
        <el-input-number v-model="form.tokenBudget" :min="0" :step="1" class="ai-config-budget" />
        <span class="control-hint">0 = 未设限（预留位）</span>
      </div>
      <div class="control-row">
        <span class="control-label">默认供应商</span>
        <el-select v-model="form.defaultProvider" class="ai-config-default-provider">
          <el-option
            v-for="row in providerRows"
            :key="row.providerId"
            :label="row.providerName"
            :value="row.providerId"
          />
        </el-select>
        <span class="control-hint">必须在四家封闭清单内</span>
      </div>
    </el-card>

    <el-card shadow="never" class="ai-config-block">
      <template #header><span class="block-title">② 当前判定预览（先开关、后预算）</span></template>
      <div class="control-row">
        <el-tag class="ai-config-preview-chip" :type="decisionTagTypeOf(preview?.decision ?? '')">
          {{ decisionLabelOf(preview?.decision ?? '') }}
        </el-tag>
        <el-button size="small" class="ai-config-preview-refresh" @click="refreshPreview">刷新预览</el-button>
      </div>
      <div class="preview-note">{{ AI_PREVIEW_NOTE }}</div>
    </el-card>

    <el-card shadow="never" class="ai-config-block">
      <template #header><span class="block-title">③ 供应商连接参数（封闭清单：glm / deepseek / qwen / kimi）</span></template>
      <el-table v-loading="loading" :data="providerRows" class="ai-config-providers" size="small">
        <el-table-column label="供应商" width="200">
          <template #default="scope">
            {{ scope.row.providerName }}
            <el-tag v-if="scope.row.isDefaultProvider" size="small" type="info" class="default-tag">默认</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="服务地址（http(s)）" min-width="260">
          <template #default="scope">
            <el-input
              v-model="form.providers[scope.$index].baseUrl"
              :placeholder="scope.row.configured ? '' : '未配置'"
              class="provider-base-url"
            />
          </template>
        </el-table-column>
        <el-table-column label="默认模型名" min-width="180">
          <template #default="scope">
            <el-input
              v-model="form.providers[scope.$index].model"
              :placeholder="scope.row.configured ? '' : '未配置'"
              class="provider-model"
            />
          </template>
        </el-table-column>
        <el-table-column label="密钥" min-width="240">
          <template #default>
            <span class="provider-key-note">{{ AI_KEY_NOTE }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="ai-config-block">
      <template #header><span class="block-title">④ 变更留痕（只读；逐字段"从何值 → 到何值"）</span></template>
      <el-table :data="changes" class="ai-config-changes" size="small" empty-text="暂无变更记录">
        <el-table-column prop="changedAt" label="时间" width="180" />
        <el-table-column prop="changedBy" label="操作者" width="140" />
        <el-table-column prop="fieldPath" label="字段" min-width="180" />
        <el-table-column label="变更前" min-width="140">
          <template #default="scope">{{ valueLabel(scope.row.beforeValue) }}</template>
        </el-table-column>
        <el-table-column label="变更后" min-width="140">
          <template #default="scope">{{ valueLabel(scope.row.afterValue) }}</template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="changesTotal > changesPageSize"
        class="pager"
        layout="prev, pager, next"
        :total="changesTotal"
        :page-size="changesPageSize"
        :current-page="changesPageNum"
        @current-change="changePage"
      />
    </el-card>

    <div class="footer-actions">
      <el-button type="primary" class="ai-config-save" :loading="saving" @click="submitSave">保存配置</el-button>
      <el-button class="ai-config-discard" @click="discard">放弃修改</el-button>
    </div>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 4px;
  font-size: 18px;
}

.page-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: #6b7280;
}

.ai-config-notice {
  margin-bottom: 12px;
}

.ai-config-error {
  margin-bottom: 12px;
}

.ai-config-block {
  margin-bottom: 16px;
}

.block-title {
  font-size: 14px;
  font-weight: 600;
  color: #374151;
}

.control-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.control-label {
  min-width: 110px;
  text-align: right;
  font-size: 13px;
  color: #6b7280;
}

.control-hint {
  font-size: 12px;
  color: #9ca3af;
}

.preview-note {
  font-size: 12px;
  color: #9ca3af;
}

.unconfigured {
  color: #c0c4cc;
  font-style: italic;
}

.provider-key-note {
  font-size: 12px;
  color: #9ca3af;
}

.default-tag {
  margin-left: 4px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.footer-actions {
  margin-top: 4px;
}
</style>
