<template>
  <el-dialog
    :model-value="modelValue"
    class="outbill-dialog"
    title="手动出账"
    width="540px"
    @update:model-value="emit('update:modelValue', $event)"
    @open="onOpen"
  >
    <el-alert
      v-if="errorTip"
      class="outbill-error"
      :title="errorTip"
      type="error"
      :closable="false"
      show-icon
    />
    <el-alert class="outbill-rule" :title="OUTBILL_RULE_HINT" type="info" :closable="false" />
    <el-alert class="outbill-auto-hint" :title="AUTO_OUTBILL_HINT" type="info" :closable="false" />
    <el-form label-width="110px">
      <el-form-item label="合约号">
        <el-input v-model="contractNo" class="outbill-contract" placeholder="CO000001" />
      </el-form-item>
      <el-form-item label="账期">
        <el-date-picker
          v-model="period"
          class="outbill-period"
          type="month"
          value-format="YYYY-MM"
          placeholder="留空由服务端按上一自然月兜底"
          clearable
        />
      </el-form-item>
      <el-form-item label="约定交易额">
        <el-input v-model="baseAmount" class="outbill-base" :placeholder="SHARE_BASE_HINT" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" class="outbill-submit" :loading="submitting" @click="submit">提交</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { outbillBill } from '../../../api/billing'
import {
  AUTO_OUTBILL_HINT,
  CONTRACT_REQUIRED_TIP,
  OUTBILL_RULE_HINT,
  SHARE_BASE_HINT,
} from '../../../constants/billing'

/**
 * 手动出账弹窗（WBS-3.7.7 hifi §3 P2 / §6）：
 * 前端仅做合约号空值拦截；账期默认上一自然月（可清空 = 服务端默认）；
 * 约定交易额常显并标注「仅分成档必填」（E0-1：出账前无法预知定价档，不在前端猜档位）；
 * 失败一律原样展示服务端文案（1011C0010 / C0013 / C0014 / C0015），弹窗不关闭。
 */
defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: [billNo: string]
}>()

const contractNo = ref('')
const period = ref<string | null>(null)
const baseAmount = ref('')
const errorTip = ref('')
const submitting = ref(false)

/** 打开时重置并预置账期 = 上一自然月（hifi §3 P2）。 */
function onOpen(): void {
  errorTip.value = ''
  if (!period.value) {
    period.value = lastMonth()
  }
}

function lastMonth(): string {
  const now = new Date()
  const target = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  return `${target.getFullYear()}-${String(target.getMonth() + 1).padStart(2, '0')}`
}

function close(): void {
  emit('update:modelValue', false)
}

async function submit(): Promise<void> {
  if (!contractNo.value.trim()) {
    errorTip.value = CONTRACT_REQUIRED_TIP
    return
  }
  submitting.value = true
  errorTip.value = ''
  try {
    const bill = await outbillBill({
      contractNo: contractNo.value.trim(),
      period: period.value ?? undefined,
      agreedTransactionAmount: baseAmount.value.trim() || undefined,
    })
    emit('success', bill.billNo)
  } catch (error) {
    errorTip.value = error instanceof Error ? error.message : String(error)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.outbill-rule {
  margin-bottom: 12px;
}

.outbill-error {
  margin-bottom: 12px;
}
</style>
