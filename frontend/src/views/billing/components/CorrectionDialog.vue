<template>
  <el-dialog
    :model-value="modelValue"
    class="correction-dialog"
    title="更正账单"
    width="540px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert class="correction-hint" :title="CORRECTION_DIALOG_TEXT" type="info" :closable="false" />
    <el-alert
      v-if="errorTip"
      class="correction-error"
      :title="errorTip"
      type="error"
      :closable="false"
      show-icon
    />
    <el-form label-width="110px">
      <el-form-item label="更正金额">
        <el-input v-model="amount" class="correction-amount" :placeholder="CORRECTION_AMOUNT_HINT" />
      </el-form-item>
      <el-form-item label="更正原因">
        <el-input
          v-model="reason"
          class="correction-reason"
          type="textarea"
          :rows="3"
          :maxlength="CORRECTION_REASON_MAX_LENGTH"
        />
        <span class="correction-counter">{{ reason.length }}/{{ CORRECTION_REASON_MAX_LENGTH }}</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" class="correction-submit" :loading="submitting" @click="submit">提交</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { createCorrection, type BillDetail } from '../../../api/billing'
import {
  CORRECTION_AMOUNT_HINT,
  CORRECTION_AMOUNT_REQUIRED_TIP,
  CORRECTION_DIALOG_TEXT,
  CORRECTION_REASON_MAX_LENGTH,
  CORRECTION_REASON_REQUIRED_TIP,
} from '../../../constants/billing'

/**
 * 更正单弹窗（WBS-3.7.7 hifi §3 P5 / §6）：
 * 金额可负（负 = 红字冲正 / 正 = 补差），前端仅做空值拦截；语义提示「不是修改原单」；
 * 成功 = 另出更正单（原单零改动），失败原样展示服务端文案（1011C0011 / C0012 / 1000C0001）。
 */
const props = defineProps<{ modelValue: boolean; billNo: string }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: [detail: BillDetail]
}>()

const amount = ref('')
const reason = ref('')
const errorTip = ref('')
const submitting = ref(false)

function close(): void {
  emit('update:modelValue', false)
}

async function submit(): Promise<void> {
  if (!amount.value.trim()) {
    errorTip.value = CORRECTION_AMOUNT_REQUIRED_TIP
    return
  }
  if (!reason.value.trim()) {
    errorTip.value = CORRECTION_REASON_REQUIRED_TIP
    return
  }
  submitting.value = true
  errorTip.value = ''
  try {
    const detail = await createCorrection(props.billNo, {
      amount: amount.value.trim(),
      reason: reason.value.trim(),
    })
    emit('success', detail)
  } catch (error) {
    errorTip.value = error instanceof Error ? error.message : String(error)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.correction-hint {
  margin-bottom: 12px;
}

.correction-error {
  margin-bottom: 12px;
}

.correction-counter {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
