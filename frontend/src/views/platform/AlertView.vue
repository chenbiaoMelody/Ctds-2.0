<template>
  <div class="platform-alert-page">
    <div class="page-head">
      <h2 class="page-title">告警与处置</h2>
      <div class="page-desc">
        告警要素七项齐备，封闭两条规则固定入列；处置经平台唯一入口（不直连 Alertmanager）。
      </div>
    </div>

    <el-alert
      v-if="error"
      class="alert-error"
      type="error"
      :title="error.message"
      :closable="false"
      show-icon
    >
      <el-button link type="primary" @click="reload">重试</el-button>
    </el-alert>

    <el-card v-else shadow="never" class="alert-card">
      <el-table
        v-loading="loading"
        class="alert-table"
        size="small"
        :data="alerts?.alerts ?? []"
        :empty-text="ALERT_EMPTY_TEXT"
      >
        <el-table-column label="告警名" min-width="190">
          <template #default="scope">
            <code class="mono">{{ scope.row.alertName }}</code>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="90">
          <template #default="scope">
            <el-tag :type="tagTypeOf(SEVERITY_TAG_TYPES, scope.row.severity)">
              {{ labelOf(SEVERITY_LABELS, scope.row.severity) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对象" min-width="150">
          <template #default="scope">{{ scope.row.target ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="触发时点" width="175">
          <template #default="scope">{{ scope.row.triggeredAt ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="当前值" width="95">
          <template #default="scope">{{ scope.row.currentValue ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="阈值" width="95">
          <template #default="scope">{{ scope.row.threshold ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="scope">
            <el-tag :type="tagTypeOf(ALERT_STATE_TAG_TYPES, scope.row.state)">
              {{ labelOf(ALERT_STATE_LABELS, scope.row.state) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态说明" min-width="170">
          <template #default="scope">
            <span class="state-note">{{ scope.row.stateNote ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="scope">
            <el-button
              v-if="canHandle"
              link
              type="primary"
              @click="openDispose(scope.row.alertName)"
            >
              处置
            </el-button>
          </template>
        </el-table-column>
        <template #empty>{{ ALERT_EMPTY_TEXT }}</template>
      </el-table>
    </el-card>

    <el-dialog v-model="disposeVisible" title="告警处置" width="560px">
      <div class="dispose-form">
        <div class="dispose-target">
          处置对象：<code class="mono">{{ disposeForm.alertName }}</code>
        </div>
        <div class="dispose-field">
          <span class="dispose-label">动作</span>
          <el-radio-group v-model="disposeForm.action">
            <el-radio
              v-for="(label, value) in DISPOSE_ACTION_LABELS"
              :key="value"
              :value="value"
            >
              {{ label }}
            </el-radio>
          </el-radio-group>
        </div>
        <div v-if="disposeForm.action === 'SILENCE'" class="dispose-field">
          <span class="dispose-label">静默时长（分钟）</span>
          <el-input-number
            v-model="disposeForm.durationMinutes"
            :min="1"
            :max="1440"
            :step="10"
          />
          <span class="dispose-hint">缺省 30，上限 1440；越界以后端裁决为准</span>
        </div>
        <div class="dispose-field">
          <span class="dispose-label">备注</span>
          <el-input v-model="disposeForm.note" type="textarea" :rows="2" placeholder="可空" />
        </div>

        <div v-if="disposeResult" class="dispose-result">
          <div class="dispose-result-title">处置响应</div>
          <div class="dispose-result-line">动作：{{ labelOf(DISPOSE_ACTION_LABELS, disposeResult.action) }}</div>
          <div class="dispose-result-line">结论：{{ disposeResult.result }}</div>
          <div v-if="disposeResult.silenceId" class="dispose-result-line">
            静默编号：<code class="mono">{{ disposeResult.silenceId }}</code>
            <el-button link type="primary" @click="copySilenceId">复制</el-button>
          </div>
          <div class="dispose-result-line">留痕时点：{{ disposeResult.recordedAt }}</div>
          <div class="dispose-result-note">处置已留痕（处置人 / 时点 / 对象 / 结论）。</div>
          <div v-if="disposeResult.action === 'ESCALATE'" class="dispose-result-note">
            通知外发通道待生效（G-04），本次处置以平台留痕为准。
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="closeDispose">关闭</el-button>
        <el-button type="primary" :loading="disposing" @click="submitDispose">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { disposeAlert, listAlerts, type AlertsView, type DisposeView } from '../../api/platform'
import {
  ALERT_STATE_LABELS,
  ALERT_STATE_TAG_TYPES,
  DISPOSE_ACTION_LABELS,
  SEVERITY_LABELS,
  SEVERITY_TAG_TYPES,
  labelOf,
  tagTypeOf,
} from '../../constants/platform'
import { getDemoSubject } from '../../api/client'
import { getActorMode, isSysadminPersona } from '../../stores/demoIdentity'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../../constants/space'

/**
 * WBS-3.9.5「告警与处置」页（hifi §4 / lofi 屏 B，menuOrder 29，platform.monitor.read）：
 * 七要素列表（封闭两规则固定入列，前端零过滤零增删）+ firing/resolved 双态徽标 +
 * 处置对话框三动作（确认 / 静默〔时长〕/ 升级）+ 处置响应回显卡（result / silenceId /
 * recordedAt；ESCALATE 附 G-04 待生效提示）。处置 = 唯一在线写面（B8-R1）；
 * 处置按钮仅系统管理员视角渲染（hifi §5 映射）；失败 = 服务端文案原样（不吞错）。
 */
const ALERT_EMPTY_TEXT = '暂无告警'

const loading = ref(false)
const alerts = ref<AlertsView | null>(null)
const error = ref<Error | null>(null)

// 处置按钮视角判定（挂载时求值，视角切换经 demoIdentityRevision 整页重挂刷新——既有惯例）
const canHandle = getActorMode() === 'operator' && isSysadminPersona()

const disposeVisible = ref(false)
const disposing = ref(false)
const disposeResult = ref<DisposeView | null>(null)
const disposeForm = reactive({
  alertName: '',
  action: 'ACKNOWLEDGE' as 'ACKNOWLEDGE' | 'SILENCE' | 'ESCALATE',
  note: '',
  durationMinutes: 30,
})

function openDispose(alertName: string): void {
  disposeForm.alertName = alertName
  disposeForm.action = 'ACKNOWLEDGE'
  disposeForm.note = ''
  disposeForm.durationMinutes = 30
  disposeResult.value = null
  disposeVisible.value = true
}

function closeDispose(): void {
  disposeVisible.value = false
}

async function submitDispose(): Promise<void> {
  disposing.value = true
  try {
    disposeResult.value = await disposeAlert({
      alertName: disposeForm.alertName,
      action: disposeForm.action,
      note: disposeForm.note.trim() ? disposeForm.note.trim() : undefined,
      durationMinutes: disposeForm.action === 'SILENCE' ? disposeForm.durationMinutes : undefined,
    })
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    disposing.value = false
  }
}

function copySilenceId(): void {
  const value = disposeResult.value?.silenceId
  if (!value) return
  void navigator.clipboard
    ?.writeText(value)
    .then(() => ElMessage.success('静默编号已复制'))
    .catch(() => ElMessage.error('复制失败，请手动选择复制'))
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    alerts.value = await listAlerts()
  } catch (err) {
    error.value = err instanceof Error ? err : new Error(String(err))
  } finally {
    loading.value = false
  }
}

function reload(): void {
  void load()
}

onMounted(() => {
  if (!getDemoSubject().trim()) {
    error.value = new Error(DEMO_SUBJECT_REQUIRED_TIP)
    return
  }
  void load()
})
</script>

<style scoped>
.platform-alert-page {
  padding: 16px;
}
.page-head {
  margin-bottom: 12px;
}
.page-title {
  margin: 0 0 4px;
  font-size: 18px;
}
.page-desc {
  color: #909399;
  font-size: 12px;
}
.alert-error {
  margin-bottom: 12px;
}
.alert-card {
  margin-bottom: 10px;
}
.state-note {
  color: #909399;
  font-size: 12px;
}
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}
.dispose-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.dispose-target {
  font-size: 13px;
  color: #303133;
}
.dispose-field {
  display: flex;
  align-items: center;
  gap: 10px;
}
.dispose-label {
  width: 110px;
  font-size: 13px;
  color: #606266;
}
.dispose-hint {
  color: #909399;
  font-size: 12px;
}
.dispose-result {
  border-top: 1px solid #ebeef5;
  padding-top: 10px;
  font-size: 13px;
}
.dispose-result-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.dispose-result-line {
  margin-bottom: 4px;
}
.dispose-result-note {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
}
</style>
