import { createRouter, createWebHistory } from 'vue-router'
import { hasPermission } from '../stores/demoRole'

/**
 * WBS-2.4.9 H3/H4 路由契约：
 * - 嵌套路由：MainLayout 为所有页面的父级布局（三区布局 H2）；
 * - 菜单由路由表驱动：子路由 meta.menu = true 自动出现在侧边栏；
 * - 权限点：meta.permission 声明所需权限点，守卫按演示角色比对；
 * - 骨架期不做真实登录校验（令牌签发归属待 3.9.1 / 2.4.12）。
 */

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题（顶栏面包屑/页面标题用） */
    title?: string
    /** 是否出现在侧边栏菜单 */
    menu?: boolean
    /** 菜单图标（Element Plus 图标组件名） */
    icon?: string
    /** 菜单排序权重（越小越靠前） */
    menuOrder?: number
    /** 所需权限点（骨架期：'demo:admin' 演示权限点） */
    permission?: string
  }
}

const routes = [
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    children: [
      {
        path: '',
        redirect: '/dashboard',
      },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('../views/dashboard/IndexView.vue'),
        meta: { title: '工作台', menu: true, menuOrder: 1, icon: 'HomeFilled' },
      },
      {
        path: 'std-capabilities',
        name: 'std-capabilities',
        component: () => import('../views/std-capabilities/IndexView.vue'),
        meta: { title: '标准能力', menu: true, menuOrder: 2, icon: 'Connection' },
      },
      {
        path: 'catalog',
        name: 'catalog',
        component: () => import('../views/catalog/IndexView.vue'),
        meta: { title: '数据目录', menu: true, menuOrder: 3, icon: 'FolderOpened' },
      },
      {
        path: 'admin-only',
        name: 'admin-only',
        component: () => import('../views/admin-only/IndexView.vue'),
        meta: { title: '仅管理员可见', menu: true, menuOrder: 4, icon: 'Lock', permission: 'demo:admin' },
      },
      {
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: () => import('../views/NotFoundView.vue'),
        meta: { title: '页面不存在' },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const required = to.meta.permission
  if (required && !hasPermission(required)) {
    return { name: 'dashboard', query: { denied: '1' } }
  }
  return true
})

export default router
export { routes }
