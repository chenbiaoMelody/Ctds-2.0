import { describe, it, expect, afterEach } from 'vitest'
import { getActorMode, setActorMode, spaceRolesHeader } from './demoIdentity'
import { getDemoSubject, setDemoSubject } from '../api/client'

/**
 * 演示身份档测试（WBS-3.2.6 hifi §4 + §7 T27）：
 * 档位读写（localStorage `ctds-demo-actor-mode`）+ 页面级角色头 `spaceRolesHeader()`。
 * 硬约束（T27 核心）：**普通档角色头不得携带 `platform.operator`**——否则成员越权类剧本步骤
 * 会被服务端角色头映射放行，让"越权被拒"步骤失真（lofi Q2 理由③）。
 * 主体编号复用 `api/client.ts` 的既有存储（不新建第二份 subject 存储）。
 */
describe('演示身份档（T27）', () => {
  afterEach(() => {
    localStorage.clear()
  })

  it('默认档位 = 普通主体（subject）', () => {
    expect(getActorMode()).toBe('subject')
  })

  it('档位写入 localStorage 键 ctds-demo-actor-mode，非法存量值回落普通档', () => {
    setActorMode('operator')
    expect(localStorage.getItem('ctds-demo-actor-mode')).toBe('operator')
    expect(getActorMode()).toBe('operator')
    localStorage.setItem('ctds-demo-actor-mode', 'root')
    expect(getActorMode()).toBe('subject')
  })

  it('普通档角色头 = applicant，且不得携带平台角色（越权步骤不失真的地基）', () => {
    setActorMode('subject')
    expect(spaceRolesHeader()).toBe('applicant')
    expect(spaceRolesHeader()).not.toContain('platform.operator')
  })

  it('运营档角色头 = applicant,platform.operator', () => {
    setActorMode('operator')
    expect(spaceRolesHeader()).toBe('applicant,platform.operator')
  })

  it('切换回普通档后角色头不再含平台角色（开关双向有效）', () => {
    setActorMode('operator')
    expect(spaceRolesHeader()).toContain('platform.operator')
    setActorMode('subject')
    expect(spaceRolesHeader()).not.toContain('platform.operator')
  })

  it('主体编号复用 client 的既有存储键 ctds-demo-subject', () => {
    setDemoSubject('S20260925000001')
    expect(getDemoSubject()).toBe('S20260925000001')
    expect(localStorage.getItem('ctds-demo-subject')).toBe('S20260925000001')
  })
})
