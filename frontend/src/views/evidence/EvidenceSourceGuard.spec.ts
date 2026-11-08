import { describe, it, expect } from 'vitest'

/**
 * 存证域静态纪律锚（WBS-3.8.5 hifi §8 T15；沿 BillingSourceGuard 范式）：
 * - 端点单点封装：evidence 端点 URL 只出现在 `api/evidence.ts`，视图与常量零直拼；
 * - 单一身份头工厂：api 层统一 `makeDomainJson(evidenceRolesHeader())`；
 * - 渲染安全：evidence 视图零 `v-html`；
 * - 请求纪律：裸 `fetch(` 在 evidence 域内恰 1 处 = `downloadAuditReport` 豁免点，
 *   且两处豁免注释锚逐字存在（№1 = delivery.ts downloadDeliveryPackage / №2 = 本文件登记）；
 * - 角色纪律：`constants/evidence.ts` 零 admin 字面量、零端点 URL；
 * - 只读铁律：视图零写面越界（无 /retry 入口、零"重试上链"文案，N9 / B5-3）。
 */
const viewSources = import.meta.glob(['./**/*.vue', './**/*.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const apiSources = import.meta.glob('../../api/evidence.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const constantsSources = import.meta.glob('../../constants/evidence.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const withoutSpecs = (sources: Record<string, string>): Record<string, string> =>
  Object.fromEntries(Object.entries(sources).filter(([path]) => !path.includes('.spec.')))

/** 剥离注释后的源码（块注释 / 行注释 / 模板注释）。 */
function withoutComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
}

function hits(source: string, pattern: string): number {
  return source.split(pattern).length - 1
}

function views(): Record<string, string> {
  return Object.fromEntries(
    Object.entries(withoutSpecs(viewSources)).map(([path, source]) => [path, withoutComments(source)]),
  )
}

const FETCH_EXEMPTION_ANCHOR =
  '裸 fetch 豁免 №2（WBS-3.8.5 Q4-A；№1 = delivery.ts downloadDeliveryPackage）'

describe('存证域源集守卫（T15）', () => {
  it('扫描目标非空：evidence 视图、api 与常量模块均被读入', () => {
    expect(Object.keys(withoutSpecs(constantsSources))).toEqual(['../../constants/evidence.ts'])
    expect(Object.keys(withoutSpecs(apiSources))).toEqual(['../../api/evidence.ts'])
    expect(Object.keys(views()).length).toBeGreaterThanOrEqual(3)
  })

  it('端点零直拼：/api/v1/evidence 只出现在 api/evidence.ts（视图与常量为零）', () => {
    const scanned = Object.values(views()).join('\n')
    expect(hits(scanned, '/api/v1/evidence')).toBe(0)
    const api = withoutComments(apiSources['../../api/evidence.ts'] ?? '')
    expect(hits(api, '/api/v1/evidence')).toBeGreaterThan(0)
    // 关键端点在 api 层各恰一处（URL 单点的精确定位）
    expect(hits(api, '/api/v1/evidence/audit/overview')).toBe(1)
    expect(hits(api, '/api/v1/evidence/audit/anomalies')).toBe(1)
    expect(hits(api, '/api/v1/evidence/audit/trails')).toBe(1)
  })

  it('单一身份头工厂：api/evidence.ts 经 makeDomainJson(evidenceRolesHeader)', () => {
    const api = withoutComments(apiSources['../../api/evidence.ts'] ?? '')
    expect(hits(api, 'makeDomainJson(evidenceRolesHeader)')).toBe(1)
  })

  it('裸 fetch( 在 evidence 域内恰 1 处（downloadAuditReport），且豁免注释锚 №2 逐字存在', () => {
    const apiSource = apiSources['../../api/evidence.ts'] ?? ''
    const scanned = Object.values(views()).join('\n') + withoutComments(apiSource)
    expect(hits(scanned, 'fetch(')).toBe(1)
    expect(hits(apiSource, FETCH_EXEMPTION_ANCHOR)).toBe(1)
  })

  it('零 v-html：evidence 视图一律模板插值（渲染安全）', () => {
    for (const [path, source] of Object.entries(views())) {
      expect(hits(source, 'v-html'), `${path} 不得出现 v-html`).toBe(0)
    }
    expect(hits('<div v-html="reason" />', 'v-html')).toBe(1)
  })

  it('constants/evidence.ts 零 admin 字面量、零端点 URL（角色串仅 demoIdentity.ts）', () => {
    const constants = withoutComments(constantsSources['../../constants/evidence.ts'] ?? '')
    expect(hits(constants, 'admin')).toBe(0)
    expect(hits(constants, '/api/')).toBe(0)
  })

  it('零 traceId / 堆栈展示（禁止内部细节上界面）', () => {
    const scanned = Object.values(views()).join('\n')
    expect(hits(scanned, 'traceId')).toBe(0)
    expect(hits(scanned, 'stack')).toBe(0)
  })

  it('零写面越界：视图无 /retry 入口、零"重试上链"文案（界面只读铁律，N9 / B5-3）', () => {
    const scanned = Object.values(views()).join('\n')
    expect(hits(scanned, '/retry')).toBe(0)
    expect(hits(scanned, '重试上链')).toBe(0)
    expect(hits(scanned, '修改')).toBe(0)
    expect(hits(scanned, '删除')).toBe(0)
  })
})
