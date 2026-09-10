<script setup lang="ts">
/**
 * WBS-2.4.9 H4 权限演示页：仅管理员可见（meta.permission = 'demo:admin'）。
 * 普通用户菜单隐藏 + 路由守卫拦截（重定向回工作台并提示）。
 */
</script>

<template>
  <div class="page">
    <h2 class="page-title">仅管理员可见</h2>
    <p class="page-desc">该页面仅管理员可见——权限机制演示（骨架期静态权限点，真实授权数据源待 3.9.x 接入）。</p>
    <el-card shadow="never">
      <el-result icon="success" title="权限校验通过" sub-title="你以管理员角色访问了受保护页面" />
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
</style>
