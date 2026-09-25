import { createRouter, createWebHistory } from 'vue-router'
import { hasPermission } from '../stores/demoRole'
import { isDemoAuthed } from '../stores/demoAuth'

/**
 * WBS-2.4.9 H3/H4 路由契约：
 * - 嵌套路由：MainLayout 为所有业务页的父级布局（三区布局 H2）；
 * - 菜单由路由表驱动：子路由 meta.menu = true 自动出现在侧边栏；
 * - 权限点：meta.permission 声明所需权限点，守卫按演示角色比对；
 * - 前端拦截仅为交互体验（菜单显隐/跳转引导）；演示期服务直连信任客户端身份头，
 *   安全边界 = 网络隔离（127.0.0.1）+ 3.5.2 网关 + 3.9.1 真实令牌（ADR-016 §2.7）；
 * - 骨架期不做真实登录校验（令牌签发归属待 3.9.1）。
 * WBS-2.4.12 增量：/login 演示登录页（B4）+ 登录守卫前置（B5/B7），
 * 既有权限守卫逻辑不变、仅置于登录守卫之后（B6）。
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
    path: '/login',
    name: 'login',
    component: () => import('../views/login/LoginView.vue'),
    meta: { title: '登录' },
  },
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
      // WBS-3.1.5 主体审核后台：真实 Vue 页面（3.1.3 lofi Q2 裁决随界面包交付）
      {
        path: 'subject/register',
        name: 'subject-register',
        component: () => import('../views/subject/RegisterView.vue'),
        meta: { title: '主体入驻', menu: true, menuOrder: 5, icon: 'OfficeBuilding' },
      },
      {
        path: 'subject/certification/:subjectNo',
        name: 'subject-certification',
        component: () => import('../views/subject/CertificationView.vue'),
        meta: { title: '认证与档案' },
      },
      {
        path: 'review',
        name: 'review-queue',
        component: () => import('../views/review/QueueView.vue'),
        meta: { title: '主体审核', menu: true, menuOrder: 6, icon: 'Checked', permission: 'subject.review' },
      },
      {
        path: 'review/:subjectNo',
        name: 'review-detail',
        component: () => import('../views/review/DetailView.vue'),
        meta: { title: '审核详情', permission: 'subject.review' },
      },
      // CHG-C-1.1-V1.2 入驻进度查询：登录即可见（无权限点，安全边界在后端双凭证比对）
      {
        path: 'subject/progress',
        name: 'subject-progress',
        component: () => import('../views/subject/ProgressQueryView.vue'),
        meta: { title: '入驻进度查询', menu: true, menuOrder: 7, icon: 'Search' },
      },
      // WBS-3.1.11 DID 管理界面：运营管理面（记录/状态/吊销/重签/重试）+ 演示与验证区
      {
        path: 'did',
        name: 'did-management',
        component: () => import('../views/did/IndexView.vue'),
        meta: { title: 'DID 管理', menu: true, menuOrder: 8, icon: 'Key', permission: 'did.admin' },
      },
      {
        path: 'did/demo',
        name: 'did-demo',
        component: () => import('../views/did/DemoView.vue'),
        meta: { title: 'DID 演示与验证', permission: 'did.admin' },
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
  // 登录守卫前置（hifi B5/B7）：未登录访问除 /login 外任何路由（含 404 兜底）→ /login；
  // 已登录访问 /login → /dashboard（不留双入口，边界值 B-2）
  if (to.name !== 'login' && !isDemoAuthed()) {
    return { name: 'login' }
  }
  if (to.name === 'login' && isDemoAuthed()) {
    return { name: 'dashboard' }
  }
  // 既有权限守卫（2.4.9 原逻辑，一字不改，B6）
  const required = to.meta.permission
  if (required && !hasPermission(required)) {
    return { name: 'dashboard', query: { denied: '1' } }
  }
  return true
})

export default router
export { routes }
