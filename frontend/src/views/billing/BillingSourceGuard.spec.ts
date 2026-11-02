import { describe, it, expect } from 'vitest'

/**
 * 计费域静态纪律锚（WBS-3.7.7 hifi §9 T15；§2.1 / §2.3 / §7）：
 * - 端点单点封装：URL 只出现在 `api/billing.ts`，视图与常量零直拼；
 * - 渲染安全：billing 视图零 `v-html`（DB-103 ②）；
 * - 角色纪律：`constants/billing.ts` 零 `admin` 字面量（门户/常量面硬约束）；
 * - 请求纪律：视图零原始 `fetch(`，一律经 api 模块。
 */
const viewSources = import.meta.glob(['./**/*.vue', './**/*.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const apiSources = import.meta.glob(['../../api/billing.ts', '../../api/order.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const constantsSources = import.meta.glob('../../constants/billing.ts', {
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

describe('计费域源集守卫（T15）', () => {
  it('扫描目标非空：billing 视图与常量、api 两模块均被读入', () => {
    expect(Object.keys(withoutSpecs(constantsSources))).toEqual(['../../constants/billing.ts'])
    expect(Object.keys(withoutSpecs(apiSources)).sort()).toEqual([
      '../../api/billing.ts',
      '../../api/order.ts',
    ])
    expect(Object.keys(views()).length).toBeGreaterThanOrEqual(7)
  })

  it('端点零直拼：/api/v1/billing 与 /api/v1/metering 只出现在 api/billing.ts', () => {
    const scanned = Object.values(views()).join('\n')
    expect(hits(scanned, '/api/v1/billing')).toBe(0)
    expect(hits(scanned, '/api/v1/metering')).toBe(0)
    expect(hits(scanned, '/api/v1/orders')).toBe(0)
    const api = withoutComments(apiSources['../../api/billing.ts'] ?? '')
    expect(hits(api, '/api/v1/billing')).toBeGreaterThan(0)
    expect(hits(api, '/api/v1/metering/flows/governance')).toBe(1)
    // 订单检索端点收口在 api/order.ts（3.7.3 既有约定）
    expect(hits(withoutComments(apiSources['../../api/order.ts'] ?? ''), '/api/v1/orders/governance')).toBe(1)
  })

  it('单一身份头工厂：api/billing.ts 经 makeDomainJson(billingRolesHeader)', () => {
    const api = withoutComments(apiSources['../../api/billing.ts'] ?? '')
    expect(hits(api, 'makeDomainJson(billingRolesHeader)')).toBe(1)
  })

  it('零原始 fetch(：视图与 api 均走 client 封装', () => {
    const scanned = Object.values(views()).join('\n') + withoutComments(apiSources['../../api/billing.ts'] ?? '')
    expect(hits(scanned, 'fetch(')).toBe(0)
  })

  it('零 v-html：billing 视图一律模板插值（DB-103 ② 渲染安全）', () => {
    for (const [path, source] of Object.entries(views())) {
      expect(hits(source, 'v-html'), `${path} 不得出现 v-html`).toBe(0)
    }
    expect(hits('<div v-html="reason" />', 'v-html')).toBe(1)
  })

  it('constants/billing.ts 零 admin 字面量、零端点 URL', () => {
    const constants = withoutComments(constantsSources['../../constants/billing.ts'] ?? '')
    expect(hits(constants, 'admin')).toBe(0)
    expect(hits(constants, '/api/')).toBe(0)
  })

  it('零 traceId / 堆栈展示（§7 禁止内部细节上界面）', () => {
    const scanned = Object.values(views()).join('\n')
    expect(hits(scanned, 'traceId')).toBe(0)
    expect(hits(scanned, 'stack')).toBe(0)
  })

  it('零计费写面越界：视图不出现直接改价 / 改账单金额入口（B10-3 结构性拒绝）', () => {
    const scanned = Object.values(views()).join('\n')
    expect(hits(scanned, '/api/v1/data-products')).toBe(0)
    expect(hits(scanned, 'updateProductPrice')).toBe(0)
    expect(hits(scanned, '修改账单金额')).toBe(0)
  })
})
