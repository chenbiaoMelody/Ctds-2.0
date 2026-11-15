import { describe, it, expect, afterEach } from 'vitest'
import {
  connectorRolesHeader,
  deliveryRolesHeader,
  evidenceRolesHeader,
  getActorMode,
  setActorMode,
  spaceRolesHeader,
  getAdminPersona,
  setAdminPersona,
  platformRolesHeader,
  rbacRolesHeader,
} from './demoIdentity'
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

describe('交付域页面级角色头（WBS-3.5.6 Q2-A / T2）', () => {
  afterEach(() => {
    localStorage.clear()
  })

  it('普通档 = requester,provider 双头（主体编号决定视角，双头使提供方动作达业务层判定）', () => {
    setActorMode('subject')
    expect(deliveryRolesHeader()).toBe('requester,provider')
  })

  it('硬约束：普通档不得携带 admin（治理面步骤不失真的地基）', () => {
    setActorMode('subject')
    expect(deliveryRolesHeader()).not.toContain('admin')
  })

  it('运营档 = admin（治理读面 + 审核写面）', () => {
    setActorMode('operator')
    expect(deliveryRolesHeader()).toBe('admin')
  })
})

/**
 * WBS-3.6.7 Q2-A：连接器域页面级角色头——普通档 applicant（申请 / 读 / 心跳 / 升级 / 注销 / 源管理），
 * 运营档 admin（核验 / 读 / 升级确认 / 注销）。硬约束 = 普通档不带 admin、任何档位不带 connector-internal。
 */
describe('连接器域页面级角色头（WBS-3.6.7 Q2-A / T2）', () => {
  afterEach(() => {
    localStorage.clear()
  })

  it('普通档 = applicant（不得混入 admin——核验类越权对照不失真）', () => {
    setActorMode('subject')
    expect(connectorRolesHeader()).toBe('applicant')
    expect(connectorRolesHeader()).not.toContain('admin')
  })

  it('运营档 = platform.secadmin,admin 组合（WBS-3.9.5 Q3-A 裁决 / DB-128 修复：核验写面 + 既有读面零回归，不随三员视角变化）', () => {
    setActorMode('operator')
    expect(connectorRolesHeader()).toBe('platform.secadmin,admin')
    setAdminPersona('audadmin')
    expect(connectorRolesHeader()).toBe('platform.secadmin,admin')
    setAdminPersona('sysadmin')
    expect(connectorRolesHeader()).toBe('platform.secadmin,admin')
  })

  it('硬约束：任何档位不含 connector-internal 及其内部权限点（内部面零接触）', () => {
    for (const mode of ['subject', 'operator'] as const) {
      setActorMode(mode)
      const header = connectorRolesHeader()
      expect(header).not.toContain('connector-internal')
      expect(header).not.toContain('connector.internal.read')
      expect(header).not.toContain('connector.internal.write')
    }
  })
})

/**
 * WBS-3.8.5 Q5-A / 前置检查 P5 实测：存证域页面级角色头——治理档 admin（evidence.governance
 * 唯一映射角色）/ 普通档 requester 单头（剧本 C-8.2 V1.2 普通档身份示例一字不差；服务端
 * 权限映射仅 admin / evidence-internal，requester 必拒 = fail-closed，即 C-8.2 S1-7 界面面）。
 * 硬约束：普通档不得携带 admin。
 */
describe('存证域页面级角色头（WBS-3.8.5 Q5-A / P5 实测）', () => {
  afterEach(() => {
    localStorage.clear()
  })

  it('普通档 = requester 单头（剧本普通档身份示例口径；不得携带 admin）', () => {
    setActorMode('subject')
    expect(evidenceRolesHeader()).toBe('requester')
    expect(evidenceRolesHeader()).not.toContain('admin')
  })

  it('运营档 = platform.audadmin（WBS-3.9.5 Q3-A 裁决 / DB-128 修复：evidence.governance 由三员种子承载，不随三员视角变化）', () => {
    setActorMode('operator')
    expect(evidenceRolesHeader()).toBe('platform.audadmin')
    setAdminPersona('sysadmin')
    expect(evidenceRolesHeader()).toBe('platform.audadmin')
    setAdminPersona('secadmin')
    expect(evidenceRolesHeader()).toBe('platform.audadmin')
  })
})

/**
 * WBS-3.9.5 Q3-A：三员视角（T15）——运营档子选择 localStorage 键 `ctds-demo-admin-persona`
 * （sysadmin〔默认〕/ secadmin / audadmin，非法值回落默认）；rbac 域与 platform 新域头随视角；
 * 既有两域（evidence / connector）固定映射不随视角（DB-128 修复口径）；普通档零管理角色回归。
 */
describe('三员视角（WBS-3.9.5 Q3-A / T15）', () => {
  afterEach(() => {
    localStorage.clear()
  })

  it('默认视角 = 系统管理员（sysadmin）；localStorage 键 ctds-demo-admin-persona', () => {
    expect(getAdminPersona()).toBe('sysadmin')
    setAdminPersona('secadmin')
    expect(getAdminPersona()).toBe('secadmin')
    expect(localStorage.getItem('ctds-demo-admin-persona')).toBe('secadmin')
  })

  it('非法存量值回落默认系统管理员（沿档位键回落惯例）', () => {
    localStorage.setItem('ctds-demo-admin-persona', 'root')
    expect(getAdminPersona()).toBe('sysadmin')
  })

  it('platform 域头随视角：sysadmin / secadmin / audadmin 三态（P7 实测矩阵口径）', () => {
    setActorMode('operator')
    setAdminPersona('sysadmin')
    expect(platformRolesHeader()).toBe('platform.sysadmin')
    setAdminPersona('secadmin')
    expect(platformRolesHeader()).toBe('platform.secadmin')
    setAdminPersona('audadmin')
    expect(platformRolesHeader()).toBe('platform.audadmin')
  })

  it('rbac 域头随视角（同 platform 新域语义分离，导出面各自独立）', () => {
    setActorMode('operator')
    setAdminPersona('audadmin')
    expect(rbacRolesHeader()).toBe('platform.audadmin')
    setAdminPersona('sysadmin')
    expect(rbacRolesHeader()).toBe('platform.sysadmin')
  })

  it('硬约束：普通主体档两域头 = requester 单头，零管理角色（fail-closed 由服务端拒）', () => {
    setActorMode('subject')
    for (const persona of ['sysadmin', 'secadmin', 'audadmin'] as const) {
      setAdminPersona(persona)
      expect(platformRolesHeader()).toBe('requester')
      expect(rbacRolesHeader()).toBe('requester')
      expect(platformRolesHeader()).not.toContain('admin')
    }
  })
})
