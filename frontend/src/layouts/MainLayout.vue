<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import { routes } from '../router'
import { getDemoRole, setDemoRole, hasPermission, type DemoRole } from '../stores/demoRole'

/**
 * WBS-2.4.9 H2 三区布局骨架：左侧菜单栏（可折叠）+ 顶部栏 + 主内容区。
 * - 菜单由路由表驱动（meta.menu = true），按 menuOrder 排序；
 * - 菜单图标由 meta.icon 解析（Element Plus 图标组件名）；
 * - 权限点路由按演示角色过滤显示；
 * - 顶栏右侧：演示模式标识 + 角色切换（骨架期静态两角色）；
 * - 无权限访问被守卫重定向时（query.denied=1）顶栏显示提示。
 */

const route = useRoute()
const router = useRouter()

const currentRole = ref<DemoRole>(getDemoRole())
const collapsed = ref(false)

const menuItems = computed(() => {
  const root = routes.find((r) => r.path === '/')
  const children = root && 'children' in root ? (root.children ?? []) : []
  return children
    .filter((r) => r.meta?.menu === true)
    // 菜单显隐与路由守卫共用同一权限点判断（单一事实来源）；显式传响应式角色触发重算
    .filter((r) => hasPermission(r.meta?.permission, currentRole.value))
    .sort((a, b) => (a.meta?.menuOrder ?? 99) - (b.meta?.menuOrder ?? 99))
})

function resolveIcon(name?: string) {
  if (!name) return null
  return (ElementPlusIconsVue as Record<string, unknown>)[name] ?? null
}

const activePath = computed(() => route.path)

const pageTitle = computed(() => route.meta?.title ?? '未命名页面')

const denied = computed(() => route.query.denied === '1')

function dismissDenied() {
  router.replace({ query: { ...route.query, denied: undefined } })
}

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
    <el-aside :width="collapsed ? '64px' : '220px'" class="sidebar">
      <div class="brand">C-TDS <span v-if="!collapsed">数据空间</span></div>
      <el-menu
        :default-active="activePath"
        :collapse="collapsed"
        router
        class="sidebar-menu"
      >
        <el-menu-item
          v-for="item in menuItems"
          :key="item.path"
          :index="'/' + item.path"
        >
          <el-icon v-if="resolveIcon(item.meta?.icon)">
            <component :is="resolveIcon(item.meta?.icon)" />
          </el-icon>
          <span>{{ item.meta?.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="topbar-left">
          <el-icon class="collapse-trigger" @click="collapsed = !collapsed">
            <component :is="resolveIcon(collapsed ? 'Expand' : 'Fold')" />
          </el-icon>
          <span class="crumb">首页 / {{ pageTitle }}</span>
        </div>
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
        <el-alert
          v-if="denied"
          title="无权限访问该页面"
          type="warning"
          :closable="true"
          show-icon
          class="denied-tip"
          @close="dismissDenied"
        />
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
  transition: width 0.2s;
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

.topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.collapse-trigger {
  font-size: 18px;
  cursor: pointer;
  color: #4b5563;
}

.collapse-trigger:hover {
  color: var(--el-color-primary);
}

.crumb {
  font-size: 13px;
  color: #6b7280;
}

.denied-tip {
  margin-bottom: 16px;
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
