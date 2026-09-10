import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createRouter, createMemoryHistory } from 'vue-router'
import MainLayout from './MainLayout.vue'
import { setDemoRole } from '../stores/demoRole'
import { routes } from '../router/index'

/**
 * WBS-2.4.9 H2/H4 布局测试：
 * - 三区布局渲染冒烟（菜单栏/顶栏/内容区）；
 * - 菜单由路由表驱动，数量 = 当前角色可见菜单路由数；
 * - 权限演示：普通用户不渲染"仅管理员可见"，admin 角色渲染。
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
