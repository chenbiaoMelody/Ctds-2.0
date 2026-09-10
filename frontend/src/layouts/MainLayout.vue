<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { routes } from '../router'
import { getDemoRole, setDemoRole, type DemoRole } from '../stores/demoRole'

/**
 * WBS-2.4.9 H2 三区布局骨架：左侧菜单栏 + 顶部栏 + 主内容区。
 * - 菜单由路由表驱动（meta.menu = true），按 menuOrder 排序；
 * - 权限点路由按演示角色过滤显示；
 * - 顶栏右侧：演示模式标识 + 角色切换（骨架期静态两角色）。
 */

const route = useRoute()
const router = useRouter()

const currentRole = ref<DemoRole>(getDemoRole())

const menuItems = computed(() => {
  const root = routes.find((r) => r.path === '/')
  const children = root && 'children' in root ? (root.children ?? []) : []
  return children
    .filter((r) => r.meta?.menu === true)
    .filter((r) => !r.meta?.permission || currentRole.value === 'admin')
    .sort((a, b) => (a.meta?.menuOrder ?? 99) - (b.meta?.menuOrder ?? 99))
})

const activePath = computed(() => route.path)

const pageTitle = computed(() => route.meta?.title ?? '未命名页面')

function onRoleChange(role: DemoRole) {
  currentRole.value = role
  setDemoRole(role)
  // 权限变化后若当前页不再可访问，回到工作台
  const required = route.meta?.permission
  if (required && role !== 'admin') {
    router.push({ name: 'dashboard' })
  }
}
</script>

<template>
  <el-container class="main-layout">
    <el-aside width="220px" class="sidebar">
      <div class="brand">C-TDS <span>数据空间</span></div>
      <el-menu :default-active="activePath" router class="sidebar-menu">
        <el-menu-item
          v-for="item in menuItems"
          :key="item.path"
          :index="'/' + item.path"
        >
          {{ item.meta?.title }}
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="crumb">首页 / {{ pageTitle }}</div>
        <div class="topbar-right">
          <el-tag size="small" type="warning">演示模式</el-tag>
          <el-select
            :model-value="currentRole"
            size="small"
            class="role-select"
            aria-label="演示角色"
            @update:model-value="onRoleChange($event as DemoRole)"
          >
            <el-option label="普通用户" value="user" />
            <el-option label="管理员" value="admin" />
          </el-select>
        </div>
      </el-header>

      <el-main class="content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.main-layout {
  height: 100%;
}

.sidebar {
  background-color: #111827;
  overflow: hidden;
}

.brand {
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 18px;
  color: #fff;
  font-weight: 600;
  letter-spacing: 1px;
  border-bottom: 1px solid #1f2937;
}

.brand span {
  color: #60a5fa;
}

.sidebar-menu {
  border-right: none;
  background-color: transparent;
}

.sidebar-menu :deep(.el-menu-item) {
  color: #d1d5db;
}

.sidebar-menu :deep(.el-menu-item:hover) {
  background-color: #1f2937;
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  background-color: var(--el-color-primary);
  color: #fff;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fff;
  border-bottom: 1px solid #e5e7eb;
}

.crumb {
  font-size: 13px;
  color: #6b7280;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.role-select {
  width: 110px;
}

.content {
  padding: 20px;
  overflow: auto;
}
</style>
