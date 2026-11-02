<template>
  <el-dialog
    :model-value="modelValue"
    class="confirm-dialog"
    title="确认账单"
    width="460px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="confirm-text">{{ CONFIRM_DIALOG_TEXT }}</p>
    <template #footer>
      <el-button class="confirm-cancel" @click="close">取消</el-button>
      <el-button type="primary" class="confirm-ok" :loading="submitting" @click="submit">确认</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmBill } from '../../../api/billing'
import { CONFIRM_DIALOG_TEXT } from '../../../constants/billing'

/**
 * 确认弹窗（WBS-3.7.7 hifi §3 P4 / §6）：
 * 二次确认 + 冻结态前置说明由详情页承载；失败（409 1011C0011 / C0012）原样展示服务端文案，
 * 并通知详情页自动刷新（不做乐观更新）。
 */
const props = defineProps<{ modelValue: boolean; billNo: string }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirmed: []
  failed: []
}>()

const submitting = ref(false)

function close(): void {
  emit('update:modelValue', false)
}

async function submit(): Promise<void> {
  submitting.value = true
  try {
    await confirmBill(props.billNo)
    emit('confirmed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error))
    emit('failed')
  } finally {
    submitting.value = false
    close()
  }
}
</script>

<style scoped>
.confirm-text {
  margin: 0;
  font-size: 14px;
}
</style>
