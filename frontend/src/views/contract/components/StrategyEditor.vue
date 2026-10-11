<template>
  <div class="strategy-editor">
    <div class="element-row">
      <el-switch v-model="quotaEnabled" />
      <span class="element-name">{{ POLICY_ELEMENT_LABELS.quota }}</span>
      <el-input
        v-if="quotaEnabled"
        v-model="quotaMaxCount"
        type="number"
        class="quota-input"
        placeholder="最大次数"
      />
      <span v-if="quotaEnabled" class="element-hint">允许的最大使用次数（非法取值由服务端判定）</span>
    </div>
    <div class="element-row">
      <el-switch v-model="termEnabled" />
      <span class="element-name">{{ POLICY_ELEMENT_LABELS.term }}</span>
      <el-date-picker
        v-if="termEnabled"
        v-model="termRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="起始日期"
        end-placeholder="截止日期"
      />
    </div>
    <div class="element-row">
      <el-switch v-model="purposeEnabled" />
      <span class="element-name">{{ POLICY_ELEMENT_LABELS.purpose }}</span>
      <el-input
        v-if="purposeEnabled"
        v-model="purposeText"
        :maxlength="200"
        placeholder="允许的用途"
      />
    </div>
    <div class="element-row">
      <el-switch v-model="territoryEnabled" />
      <span class="element-name">{{ POLICY_ELEMENT_LABELS.territory }}</span>
      <el-input
        v-if="territoryEnabled"
        v-model="territoryText"
        :maxlength="200"
        placeholder="域内范围"
      />
    </div>
    <div class="element-row">
      <el-switch v-model="noRedistributionEnabled" />
      <span class="element-name">{{ POLICY_ELEMENT_LABELS.noRedistribution }}</span>
    </div>
    <el-checkbox v-model="noRestrictionDeclared">无使用限制（显式声明；与启用要素的互斥由服务端判定）</el-checkbox>
  </div>
</template>

<script setup lang="ts">
/**
 * 策略编辑器（WBS-3.4.7 hifi §2；Q6-A）：五要素（次数开关 + 整数输入〔**不设前端下限**——
 * 非法值可提交交服务端 1008C0015 判定〕/ 期限开关 + 起止日期 / 用途·域内开关 + 文本 /
 * 禁止再分发开关）+ 显式"无使用限制"勾选。输出对象键固定与后端策略文档字段名一致：
 * `{quota:{enabled,maxCount},term:{enabled,startDate,endDate},purpose:{enabled,text},
 * territory:{enabled,text},noRedistribution:{enabled},noRestrictionDeclared}`。
 */
import { ref, watch } from 'vue'
import { POLICY_ELEMENT_LABELS } from '../../../constants/contract'

const props = defineProps<{
  modelValue?: Record<string, unknown> | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, unknown>): void
}>()

const quotaEnabled = ref(false)
const quotaMaxCount = ref('')
const termEnabled = ref(false)
const termRange = ref<[string, string] | null>(null)
const purposeEnabled = ref(false)
const purposeText = ref('')
const territoryEnabled = ref(false)
const territoryText = ref('')
const noRedistributionEnabled = ref(false)
const noRestrictionDeclared = ref(false)

function toIntOrNull(raw: string): number | null {
  if (raw.trim() === '') {
    return null
  }
  const parsed = Number(raw)
  return Number.isFinite(parsed) ? Math.trunc(parsed) : null
}

function buildStrategy(): Record<string, unknown> {
  return {
    quota: {
      enabled: quotaEnabled.value,
      maxCount: quotaEnabled.value ? toIntOrNull(quotaMaxCount.value) : null,
    },
    term: {
      enabled: termEnabled.value,
      startDate: termEnabled.value ? (termRange.value?.[0] ?? null) : null,
      endDate: termEnabled.value ? (termRange.value?.[1] ?? null) : null,
    },
    purpose: {
      enabled: purposeEnabled.value,
      text: purposeEnabled.value ? (purposeText.value || null) : null,
    },
    territory: {
      enabled: territoryEnabled.value,
      text: territoryEnabled.value ? (territoryText.value || null) : null,
    },
    noRedistribution: { enabled: noRedistributionEnabled.value },
    noRestrictionDeclared: noRestrictionDeclared.value,
  }
}

watch(
  [quotaEnabled, quotaMaxCount, termEnabled, termRange, purposeEnabled, purposeText,
    territoryEnabled, territoryText, noRedistributionEnabled, noRestrictionDeclared],
  () => emit('update:modelValue', buildStrategy()),
  { deep: true, immediate: true },
)
</script>

<style scoped>
.strategy-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.element-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.element-name {
  min-width: 72px;
}

.element-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.quota-input {
  max-width: 160px;
}
</style>
