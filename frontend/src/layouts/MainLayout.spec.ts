import { describe, it, expect, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import MainLayout from './MainLayout.vue'
import { setDemoRole } from '../stores/demoRole'
import { routes } from '../router/index'

/**
 * WBS-2.4.9 H2/H4 布局测试：
 * - 三区布局渲染冒烟（菜单栏/顶栏/内容区）；
 * - 菜单由路由表驱动，数量 = 当前角色可见菜单路由数；
 * - 权限演示：普通用户不渲染"仅管理员可见"，admin 角色渲染；
 * - 无权限重定向提示（denied=1）：提示条渲染 + 关闭后清除 query。
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

  it('普通用户：菜单渲染 3 项，不含"仅管理员可见"', () => {
    setDemoRole('user')
    const wrapper = mountLayout()
    const items = wrapper.findAll('.el-menu-item')
    expect(items.length).toBe(3)
    expect(wrapper.text()).not.toContain('仅管理员可见')
  })

  it('admin 角色：菜单渲染 4 项，含"仅管理员可见"', () => {
    setDemoRole('admin')
    const wrapper = mountLayout()
    const items = wrapper.findAll('.el-menu-item')
    expect(items.length).toBe(4)
    expect(wrapper.text()).toContain('仅管理员可见')
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
