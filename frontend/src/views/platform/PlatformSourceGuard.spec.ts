import { describe, it, expect } from 'vitest'

/**
 * 平台系统管理界面源集守卫（WBS-3.9.5 hifi §2 / 任务卡关闭条件⑤，沿 ConnectorSourceGuard 等 8 域先例）：
 * ① URL 单点：rbac 4 端点 + platform 4 端点 URL 仅存在于 `api/platform.ts`（视图零 `/api/` 字面量）；
 * ② 零 v-html（诚实呈现，不注入 HTML）；
 * ③ 零角色头字面量散写（角色头收口 `demoIdentity.ts`；三员视角判定走 helper，视图不写 persona 字面量）；
 * ④ 零服务端错误码 / 文案副本（一律展示 ApiError.message）；
 * ⑤ 零裸 fetch（域请求走 makeDomainJson 域工厂 → client.apiJson）；
 * ⑥ 零内部面（/internal/）；
 * ⑦ 零阈值编辑 / 生命周期操作入口（Q1-A 无阈值编辑 UI + B9-R3 零操作按钮的源面守卫）。
 */
const viewSources = import.meta.glob('./*.vue', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const apiSources = import.meta.glob('../../api/platform.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const constantSources = import.meta.glob('../../constants/platform.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const viewFiles = Object.keys(viewSources)
const apiText = Object.values(apiSources).join('')
const constantText = Object.values(constantSources).join('')

const SERVER_CODE_LITERALS = [
  '1013C0001',
  '1013S0002',
  '1013S0003',
  '1000C0005',
  '1000C0002',
  '1013S0001',
]

describe('平台系统管理界面源集守卫（WBS-3.9.5；WBS-3.10.4 扩 AI 服务配置）', () => {
  it('platform 视图源集存在且为 5 个（监控读数 / 告警与处置 / 角色与权限 / 部署域 / AI 服务配置）', () => {
    expect(viewFiles.sort()).toEqual([
      './AiConfigView.vue',
      './AlertView.vue',
      './DomainView.vue',
      './MonitorView.vue',
      './RbacView.vue',
    ])
  })

  it('URL 单点：8 条端点 URL 全部收口 api/platform.ts；视图与常量零 /api/ 字面量', () => {
    for (const url of [
      '/api/v1/rbac/roles',
      '/api/v1/rbac/permissions/grant',
      '/api/v1/rbac/permissions/revoke',
      '/api/v1/rbac/changes',
      '/api/v1/platform/domain',
      '/api/v1/platform/metrics',
      '/api/v1/platform/alerts',
      '/api/v1/platform/alerts/actions',
    ]) {
      expect(apiText, `URL ${url} 须收口 api/platform.ts`).toContain(url)
    }
    for (const [file, source] of Object.entries({ ...viewSources, '../../constants/platform.ts': constantText })) {
      // URL 字面量形态（引号后紧跟 /api/）；import 相对路径（../../api/…）不算散写
      expect(source, `${file} 散写了 URL`).not.toMatch(/['"]\/api\//)
    }
  })

  it('零 v-html（诚实呈现铁律）', () => {
    for (const source of [apiText, constantText, ...Object.values(viewSources)]) {
      expect(source).not.toContain('v-html')
    }
  })

  it('零角色头字面量散写：视图不写角色头与 persona 值（收口 demoIdentity.ts）', () => {
    for (const [file, source] of Object.entries(viewSources)) {
      expect(source, `${file} 出现角色头字面量`).not.toContain("'platform.sysadmin'")
      expect(source, `${file} 出现角色头字面量`).not.toContain("'platform.secadmin'")
      expect(source, `${file} 出现角色头字面量`).not.toContain("'platform.audadmin'")
      expect(source, `${file} 出现 admin 角色字面量`).not.toContain("'admin'")
      expect(source, `${file} 出现 persona 字面量`).not.toContain("'sysadmin'")
      expect(source, `${file} 出现 persona 字面量`).not.toContain("'secadmin'")
      expect(source, `${file} 出现 persona 字面量`).not.toContain("'audadmin'")
    }
    expect(apiText).not.toContain("'admin'")
  })

  it('零服务端错误码 / 文案副本：视图与 API 不出现九位码字面量（一律 ApiError.message）', () => {
    for (const [file, source] of Object.entries({ ...viewSources, '../../api/platform.ts': apiText })) {
      for (const code of SERVER_CODE_LITERALS) {
        expect(source, `${file} 内置了服务端错误码 ${code}`).not.toContain(code)
      }
    }
    for (const source of Object.values(viewSources)) {
      expect(source).not.toContain('无权限执行该操作')
      expect(source).not.toContain('监控数据源暂不可用')
      expect(source).not.toContain('部署域配置与实际不一致')
    }
  })

  it('零裸 fetch：视图与 api/platform.ts 不直接调用 fetch（域工厂 → client.apiJson）', () => {
    for (const source of [apiText, ...Object.values(viewSources)]) {
      expect(source).not.toMatch(/\bfetch\s*\(/)
    }
  })

  it('零幂等键（评审② P3 补强：platform 域 3 个 POST 端点未启用 X-Idempotency-Key；grant 幂等由三元组唯一键承载）', () => {
    for (const source of [apiText, ...Object.values(viewSources)]) {
      expect(source).not.toContain('X-Idempotency-Key')
      expect(source).not.toContain('idempotencyKey')
    }
  })

  it('零内部面：视图 / API / 常量均不含 /internal/', () => {
    for (const source of [apiText, constantText, ...Object.values(viewSources)]) {
      expect(source).not.toContain('/internal/')
    }
  })

  it('零阈值编辑与生命周期操作入口（Q1-A / B9-R3 / N1 / N2 源面守卫）', () => {
    for (const [file, source] of Object.entries(viewSources)) {
      expect(source, `${file} 出现阈值编辑入口`).not.toContain('编辑阈值')
      expect(source, `${file} 出现阈值新增/修改`).not.toMatch(/阈值.*(新增|修改|编辑)|(新增|修改|编辑).*阈值/)
      expect(source, `${file} 出现生命周期操作`).not.toMatch(/(开通|升级|退役).*(按钮|确认|执行)/)
    }
    expect(apiText).not.toContain('PUT ')
    expect(apiText).not.toContain('DELETE ')
  })
})
