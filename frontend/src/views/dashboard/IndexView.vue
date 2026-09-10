<script setup lang="ts">
/**
 * WBS-2.4.9 H7 工作台示例页：
 * 统计卡片 + 表格 + 按钮/表单的 Element Plus 组件综合示例，展示布局/组件/主题。
 */
const stats = [
  { label: '标准能力域', value: 3 },
  { label: '已开放能力', value: 0 },
  { label: '示例页面', value: 1 },
]

const capabilityRows = [
  { name: '互联互通', status: '未开放', desc: '对接国家/区域枢纽（占位）' },
  { name: '跨空间身份互认', status: '未开放', desc: '政务 CA / 跨空间身份（占位）' },
  { name: '测评证据', status: '未开放', desc: '信通院测评证据采集（占位）' },
]
</script>

<template>
  <div class="page">
    <h2 class="page-title">工作台</h2>
    <p class="page-desc">前端框架骨架示例页：布局 / 菜单 / 组件 / 主题的统一载体。</p>

    <el-alert
      title="权限演示：在顶栏切换到「管理员」角色，左侧菜单将出现「仅管理员可见」入口；切回「普通用户」即隐藏。"
      type="info"
      :closable="false"
      show-icon
      class="demo-tip"
    />

    <el-row :gutter="16" class="stat-row">
      <el-col v-for="s in stats" :key="s.label" :span="8">
        <el-card shadow="hover">
          <div class="stat-num">{{ s.value }}</div>
          <div class="stat-label">{{ s.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="section">
      <template #header>标准能力状态（静态示例，与「标准能力」页同数据形态）</template>
      <el-table :data="capabilityRows" size="default">
        <el-table-column prop="name" label="能力域" min-width="160" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag type="info" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="desc" label="说明" min-width="240" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>Element Plus 组件示例</template>
      <div class="component-demo">
        <el-button type="primary">主要按钮</el-button>
        <el-button>次要按钮</el-button>
        <el-input placeholder="输入框占位" class="demo-input" />
        <el-switch />
      </div>
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

.demo-tip {
  margin-bottom: 16px;
}

.stat-row {
  margin-bottom: 16px;
}

.stat-num {
  font-size: 26px;
  font-weight: 700;
  color: var(--el-color-primary);
}

.stat-label {
  font-size: 13px;
  color: #6b7280;
  margin-top: 4px;
}

.section {
  margin-bottom: 16px;
}

.component-demo {
  display: flex;
  align-items: center;
  gap: 12px;
}

.demo-input {
  width: 200px;
}
</style>
