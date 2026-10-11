<template>
  <div class="clause-editor">
    <template v-if="mode === 'framework' && framework">
      <div v-for="slot in framework.slots" :key="slot.key" class="clause-slot-row">
        <div class="slot-head">
          <span class="slot-name">{{ slot.name }}</span>
          <el-tag v-if="slot.required" size="small" type="danger">必填</el-tag>
          <el-tag v-else size="small" type="info">可选</el-tag>
        </div>
        <div v-if="slot.guide" class="slot-guide">{{ slot.guide }}</div>
        <el-input
          :model-value="modelValue.slots[slot.key] ?? ''"
          :maxlength="2000"
          :placeholder="slot.guide ?? ''"
          @update:model-value="setSlot(slot.key, String($event))"
        />
      </div>
    </template>
    <template v-else>
      <div v-for="key in Object.keys(modelValue.slots)" :key="key" class="clause-slot-row">
        <div class="slot-head">
          <span class="slot-key">{{ key }}</span>
          <el-button size="small" text type="danger" @click="removeSlot(key)">删除</el-button>
        </div>
        <el-input
          :model-value="modelValue.slots[key]"
          :maxlength="2000"
          @update:model-value="setSlot(key, String($event))"
        />
      </div>
      <div v-for="(row, index) in newRows" :key="`new-${index}`" class="clause-slot-row new-row">
        <el-input
          v-model="row.key"
          placeholder="键（英文标识）"
          class="new-key"
          @update:model-value="syncNewRows"
        />
        <el-input
          v-model="row.value"
          placeholder="值"
          :maxlength="2000"
          class="new-value"
          @update:model-value="syncNewRows"
        />
        <el-button size="small" text type="danger" @click="removeNewRow(index)">删除</el-button>
      </div>
      <el-button size="small" @click="addRow">新增条款项</el-button>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 条款槽位编辑器（WBS-3.4.7 hifi §2；Q6-A）：
 * - **框架模式**（发起）：按 `clauseFramework.slots` 逐槽位渲染行——名称 / 必填标记 /
 *   填写说明 / 值输入（maxlength ≤2000 对齐后端口径）；
 * - **值模式**（提案）：按当前 `clauseValues.slots` 渲染——**键只读**（文本渲染）+ 值可改 +
 *   新增行（键 + 值）+ 行删除；非法取值（如空键）不前端硬拦，由服务端 1008C0014/C0015 判定
 *   （剧本 C-4.3 S1-3/S1-4 屏幕可执行）。
 */
import { ref } from 'vue'

interface FrameworkSlot {
  key: string
  name: string
  required: boolean
  guide: string | null
}

const props = defineProps<{
  mode: 'framework' | 'values'
  framework?: { slots: FrameworkSlot[] } | null
  modelValue: { slots: Record<string, string> }
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: { slots: Record<string, string> }): void
}>()

const newRows = ref<{ key: string; value: string }[]>([])

function emitSlots(slots: Record<string, string>): void {
  emit('update:modelValue', { slots })
}

function setSlot(key: string, value: string): void {
  emitSlots({ ...props.modelValue.slots, [key]: value })
}

function removeSlot(key: string): void {
  const slots = { ...props.modelValue.slots }
  delete slots[key]
  emitSlots(slots)
}

function addRow(): void {
  newRows.value.push({ key: '', value: '' })
}

function removeNewRow(index: number): void {
  newRows.value.splice(index, 1)
  syncNewRows()
}

function syncNewRows(): void {
  const slots = { ...props.modelValue.slots }
  for (const row of newRows.value) {
    if (row.key.trim()) {
      slots[row.key.trim()] = row.value
    }
  }
  emitSlots(slots)
}
</script>

<style scoped>
.clause-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.clause-slot-row .slot-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.slot-key {
  font-family: monospace;
  color: var(--el-text-color-secondary);
}

.slot-guide {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

.new-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.new-row .new-key {
  max-width: 220px;
}
</style>
