<script setup lang="ts">
import { onMounted, ref } from 'vue'

/**
 * WBS-2.4.9 H8 标准能力示例页：
 * 真实调用 example-service `GET /api/v1/std-capabilities`（Vite dev proxy 转发 localhost:8080），
 * 展示三个标准域（互联互通/跨空间身份互认/测评证据）"未开放"状态。
 * 含加载中 / 成功 / 失败三态处理。
 */

interface StdCapabilityView {
  code: string
  name: string
  implemented: boolean
  message: string
}

interface ApiResult<T> {
  code: string
  message?: string
  traceId?: string
  data?: T
}

const loading = ref(true)
const errorMessage = ref('')
const capabilities = ref<StdCapabilityView[]>([])

async function loadCapabilities() {
  loading.value = true
  errorMessage.value = ''
  try {
    const resp = await fetch('/api/v1/std-capabilities')
    const result = (await resp.json()) as ApiResult<StdCapabilityView[]>
    if (result.code === '0' && result.data) {
      capabilities.value = result.data
    } else {
      errorMessage.value = '标准能力服务返回异常，请稍后重试。'
    }
    } catch {
      errorMessage.value = '标准能力服务暂不可用，请稍后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(loadCapabilities)
</script>

<template>
  <div class="page">
    <h2 class="page-title">标准能力</h2>
    <p class="page-desc">
      标准适配层能力状态（WBS 2.4.8）：数据来自 example-service 真实接口，展示前后端联通。
    </p>

    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      :closable="false"
      show-icon
      class="section"
    >
      <template #default>
        <el-button size="small" class="retry-btn" @click="loadCapabilities">重试</el-button>
      </template>
    </el-alert>

    <el-card v-if="loading" shadow="never" class="section">
      <el-skeleton :rows="3" animated />
    </el-card>

    <el-card v-else-if="capabilities.length === 0 && !errorMessage" shadow="never" class="section">
      <el-empty description="暂无标准能力域" />
    </el-card>

    <el-card v-else-if="capabilities.length > 0" shadow="never" class="section">
      <template #header>标准能力域状态</template>
      <el-table :data="capabilities">
        <el-table-column prop="code" label="能力域编码" min-width="140" />
        <el-table-column prop="name" label="能力域" min-width="160" />
        <el-table-column label="状态" width="120">
          <template #default="scope">
            <el-tag v-if="scope.row && scope.row.implemented" type="success" size="small">已开放</el-tag>
            <el-tag v-else type="info" size="small">未开放</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="280" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 4px;
  font-size: 18px;
}

.page-desc {
  margin: 0 0 16px;
  font-size: 13px;
  color: #6b7280;
}

.section {
  margin-bottom: 16px;
}

.retry-btn {
  margin-top: 8px;
}
</style>
