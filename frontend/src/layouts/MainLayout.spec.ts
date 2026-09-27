import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import MainLayout from './MainLayout.vue'
import { setDemoRole, getDemoRole } from '../stores/demoRole'
import { ACTOR_MODE_LABELS, getActorMode } from '../stores/demoIdentity'
import { isDemoAuthed, signInDemo } from '../stores/demoAuth'
import { routes } from '../router/index'

/**
 * WBS-2.4.9 H2/H4 布局测试：
 * - 三区布局渲染冒烟（菜单栏/顶栏/内容区）；
 * - 菜单由路由表驱动，数量 = 当前角色可见菜单路由数；
 * - 权限演示：普通用户不渲染"仅管理员可见"，admin 角色渲染；
 * - 无权限重定向提示（denied=1）：提示条渲染 + 关闭后清除 query。
 * WBS-2.4.12 增量（B8）：顶栏"退出"清登录态回登录页（本文件自建 router 实例不挂守卫，
 * 仅验证按钮行为本身；守卫拦截行为在 router.spec 覆盖）。
 */
const router = createRouter({ history: createMemoryHistory(), routes })

const mountLayout = () =>
  mount(MainLayout, {
    global: {
      plugins: [ElementPlus, router],
    },
  })

describe('三区布局渲染（H2）', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('渲染布局骨架与业务占位内容', () => {
    const wrapper = mountLayout()
    expect(wrapper.find('.brand').text()).toContain('C-TDS')
    expect(wrapper.find('.topbar').exists()).toBe(true)
    expect(wrapper.find('.content').exists()).toBe(true)
    expect(wrapper.find('.sidebar').exists()).toBe(true)
  })

  it('顶栏显示演示模式标识', () => {
    const wrapper = mountLayout()
    expect(wrapper.find('.topbar').text()).toContain('演示模式')
  })
})

describe('权限菜单显隐（H4）', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('普通用户：菜单渲染 5 项，不含权限保护菜单（CHG-C-1.1-V1.2 新增入驻进度查询菜单）', () => {
    setDemoRole('user')
    const wrapper = mountLayout()
    const items = wrapper.findAll('.el-menu-item')
    expect(items.length).toBe(5)
    expect(wrapper.text()).not.toContain('仅管理员可见')
    expect(wrapper.text()).not.toContain('主体审核')
    expect(wrapper.text()).not.toContain('DID 管理')
    expect(wrapper.text()).toContain('入驻进度查询')
  })

  it('admin 角色：菜单渲染 10 项，含"仅管理员可见"、"主体审核"、"DID 管理"与空间域两项（WBS-3.2.6 新增）', () => {
    setDemoRole('admin')
    const wrapper = mountLayout()
    const items = wrapper.findAll('.el-menu-item')
    expect(items.length).toBe(10)
    expect(wrapper.text()).toContain('仅管理员可见')
    expect(wrapper.text()).toContain('主体审核')
    expect(wrapper.text()).toContain('DID 管理')
    expect(wrapper.text()).toContain('逻辑空间')
    expect(wrapper.text()).toContain('平台策略治理')
  })
})

describe('顶栏"演示身份"控件（WBS-3.2.6 §6.1 / T27）', () => {
  beforeEach(() => {
    localStorage.clear()
    document.body.innerHTML = ''
  })

  it('渲染主体编号输入框、档位下拉（普通主体 / 平台运营方）与保存按钮', () => {
    const wrapper = mountLayout()
    expect(wrapper.find('.identity-subject').exists()).toBe(true)
    expect(wrapper.find('.identity-save').exists()).toBe(true)
    expect(wrapper.find('.identity-mode').exists()).toBe(true)
    // 默认档位 = 普通主体（值口径；中文标签由 ACTOR_MODE_LABELS 收口）
    expect(getActorMode()).toBe('subject')
    expect(ACTOR_MODE_LABELS.subject).toBe('普通主体')
    expect(ACTOR_MODE_LABELS.operator).toBe('平台运营方')
  })

  it('主体编号输入框占位提示与界面说明书一致（R5 / §6.1）', () => {
    const wrapper = mountLayout()
    expect(wrapper.find('.identity-subject input').attributes('placeholder')).toBe('如 S20260925000001')
  })

  it('保存：主体编号写入既有存储，档位写入演示身份存储并提示', async () => {
    const wrapper = mountLayout()
    await wrapper.find('.identity-subject input').setValue('S20260925000001')
    await wrapper.findComponent('.identity-mode').setValue('operator')
    await wrapper.find('.identity-save').trigger('click')
    await flushPromises()
    expect(localStorage.getItem('ctds-demo-subject')).toBe('S20260925000001')
    expect(localStorage.getItem('ctds-demo-actor-mode')).toBe('operator')
    expect(document.body.textContent).toContain('已切换演示身份')
  })

  it('主体编号留空：前置拦截（不写入任何存储）', async () => {
    const wrapper = mountLayout()
    await wrapper.find('.identity-subject input').setValue('   ')
    await wrapper.find('.identity-save').trigger('click')
    await flushPromises()
    expect(localStorage.getItem('ctds-demo-actor-mode')).toBeNull()
    expect(document.body.textContent).toContain('请先填写演示身份主体编号')
  })
})

describe('无权限访问提示（H4 边界值）', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('带 denied=1 query 进入：内容区顶部渲染"无权限访问该页面"提示条', async () => {
    await router.push('/dashboard?denied=1')
    await router.isReady()
    const wrapper = mountLayout()
    expect(wrapper.find('.denied-tip').exists()).toBe(true)
    expect(wrapper.text()).toContain('无权限访问该页面')
  })

  it('关闭提示条：query 中 denied 被清除', async () => {
    await router.push('/dashboard?denied=1')
    await router.isReady()
    const wrapper = mountLayout()
    await wrapper.find('.el-alert__close-btn').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.denied).toBeUndefined()
    expect(wrapper.find('.denied-tip').exists()).toBe(false)
  })

  it('不带 denied query 进入：不渲染提示条', async () => {
    await router.push('/dashboard')
    await router.isReady()
    const wrapper = mountLayout()
    expect(wrapper.find('.denied-tip').exists()).toBe(false)
  })
})

describe('顶栏退出（WBS-2.4.12 B8）', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('点击退出：清演示登录态（角色保留）并跳登录页', async () => {
    setDemoRole('admin')
    signInDemo()
    await router.push('/dashboard')
    await router.isReady()
    const wrapper = mountLayout()
    await wrapper.find('.signout-btn').trigger('click')
    // router.push 为组件内异步调用，轮询等待导航完成（flushPromises 次数对时序敏感，门禁环境曾复现不足）
    await vi.waitFor(() => {
      expect(router.currentRoute.value.name).toBe('login')
    })
    expect(isDemoAuthed()).toBe(false)
    // B3 角色保留（评审④P3-1 补）：退出只清登录态，演示角色不受影响
    expect(getDemoRole()).toBe('admin')
  })
})
