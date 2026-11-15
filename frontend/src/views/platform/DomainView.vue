<template>
  <div class="platform-domain-page">
    <div class="page-head">
      <h2 class="page-title">部署域</h2>
      <div class="page-desc">
        租户（部署域）配置只读呈现；生命周期动作由部署变更执行，页面零操作按钮（B9-R3）。
      </div>
    </div>

    <el-alert
      v-if="error"
      class="domain-error"
      type="error"
      :title="error.message"
      :closable="false"
      show-icon
    >
      <el-button link type="primary" @click="reload">重试</el-button>
    </el-alert>

    <el-card v-else v-loading="loading" shadow="never" class="domain-card">
      <el-descriptions :column="1" border size="small" class="domain-descriptions">
        <el-descriptions-item label="部署域标识">
          <code class="mono">{{ domain?.domainId || '—' }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="部署域名称">
          {{ domain?.domainName || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="平台版本">
          {{ domain?.platformVersion || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="生命周期状态">
          <el-tag :type="tagTypeOf(LIFECYCLE_TAG_TYPES, domain?.lifecycleStatus)">
            {{ labelOf(LIFECYCLE_LABELS, domain?.lifecycleStatus) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="库 schema 清单">
          {{ listText(domain?.schemaList) }}
        </el-descriptions-item>
        <el-descriptions-item label="服务清单">
          {{ listText(domain?.serviceList) }}
        </el-descriptions-item>
        <el-descriptions-item label="配置一致性结论">
          <el-tag :type="tagTypeOf(CONSISTENCY_TAG_TYPES, domain?.configConsistency)">
            {{ labelOf(CONSISTENCY_LABELS, domain?.configConsistency) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="口径声明">
          <span class="scope-note">{{ domain?.scopeNote || '—' }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getDomain, type DomainView } from '../../api/platform'
import {
  CONSISTENCY_LABELS,
  CONSISTENCY_TAG_TYPES,
  LIFECYCLE_LABELS,
  LIFECYCLE_TAG_TYPES,
  labelOf,
  tagTypeOf,
} from '../../constants/platform'
import { getDemoSubject } from '../../api/client'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../../constants/space'

/**
 * WBS-3.9.5「部署域」信息页（hifi §4 / lofi 屏 D，menuOrder 31，platform.domain.read）：
 * 八字段只读卡（scopeNote 原文透传；lifecycleStatus / configConsistency 徽标映射；
 * RETIRED 灰徽标信息卡照常——端点豁免可读）。配置不一致 = HTTP 503（九位码与文案见规格 B9-3，服务端权威）
 * → 整页错误态原文 + 重试，**不显示"正常"**（B9-R4 fail-closed）。零操作按钮（B9-R3）。
 */
const loading = ref(false)
const domain = ref<DomainView | null>(null)
const error = ref<Error | null>(null)

function listText(list: string[] | undefined): string {
  if (!list || list.length === 0) return '—'
  return list.join('、')
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    domain.value = await getDomain()
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
.platform-domain-page {
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
.domain-error {
  margin-bottom: 12px;
}
.domain-card {
  max-width: 760px;
}
.scope-note {
  color: #909399;
  font-size: 12px;
}
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}
</style>
