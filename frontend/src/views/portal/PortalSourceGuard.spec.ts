import { describe, it, expect } from 'vitest'
import {
  formatPortalPricing,
  pricingModelTagType,
  PRODUCT_NOT_ACCESSIBLE_TIP,
  PORTAL_ORDER_PLACEHOLDER_TIP,
  PORTAL_BROWSE_DESC,
} from '../../constants/catalog'

/**
 * 门户界面源集守卫与定价展示助手测试（WBS-3.7.2 hifi §2.2 / §8.1 T9）：
 * 以 Vite 原始文本导入（`?raw`）断言门户视图与 API 模块的静态纪律，并验证共享常量助手。
 */
const viewSources = import.meta.glob('./*.vue', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const apiSources = import.meta.glob('../../api/catalog.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const constantsSources = import.meta.glob('../../constants/catalog.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const identitySources = import.meta.glob('../../stores/demoIdentity.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const withoutSpecs = (sources: Record<string, string>): Record<string, string> =>
  Object.fromEntries(Object.entries(sources).filter(([path]) => !path.includes('.spec.')))

function uiScanned(): string {
  return [...Object.values(withoutSpecs(viewSources)), ...Object.values(withoutSpecs(apiSources))].join('\n')
}

function hits(source: string, pattern: string): number {
  return source.split(pattern).length - 1
}

/** 剥离注释后的源码（块注释 / 行注释 / 模板注释）。 */
function withoutComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
}

function view(name: string): string {
  return withoutComments(viewSources[`./${name}`] ?? '')
}

describe('门户源集守卫（T9）', () => {
  it('扫描目标非空：两个门户视图 + api/catalog.ts + constants/catalog.ts 均被读入', () => {
    expect(Object.keys(withoutSpecs(viewSources)).sort()).toEqual([
      './PortalHomeView.vue',
      './PortalProductDetailView.vue',
    ])
    expect(Object.keys(withoutSpecs(apiSources)).length).toBe(1)
    expect(Object.keys(withoutSpecs(constantsSources)).length).toBe(1)
  })

  it('零 /internal/ 调用、零治理读面调用（GOVERNANCE_VIEW 留痕面不得用于前台浏览）', () => {
    // /internal/ 对门户视图与 API 模块全域禁用；治理函数 / 路径仅约束门户视图
    // （api/catalog.ts 为其他管理页保留治理函数导出，门户不得引用）
    expect(hits(uiScanned(), '/internal/')).toBe(0)
    const portalViews = [view('PortalHomeView.vue'), view('PortalProductDetailView.vue')].join('\n')
    expect(hits(portalViews, 'getProductGovernance')).toBe(0)
    expect(hits(portalViews, 'getDatasetGovernance')).toBe(0)
    expect(hits(portalViews, '/governance')).toBe(0)
    // 反向探针：违规样本必命中
    expect(hits("apiJson('/internal/x')", '/internal/')).toBe(1)
    expect(hits('getProductGovernance(7)', 'getProductGovernance')).toBe(1)
  })

  it('零原始 fetch(：一律经 api/catalog.ts 的 apiJson / makeDomainJson 封装', () => {
    expect(hits(withoutComments(uiScanned()), 'fetch(')).toBe(0)
    expect(hits(withoutComments(apiSources['../../api/catalog.ts'] ?? ''), 'makeDomainJson(catalogRolesHeader)'))
      .toBe(1)
    // 反向探针
    expect(hits('const r = await fetch(url)', 'fetch(')).toBe(1)
  })

  it("'admin' 字面量不得出现在门户视图（允许面仅 demoIdentity.ts 与 api/catalog.ts）", () => {
    const allowed = new Set([...Object.keys(withoutSpecs(apiSources)), ...Object.keys(identitySources)])
    const scanned = { ...withoutSpecs(viewSources), ...withoutSpecs(constantsSources) }
    const offenders = Object.entries(scanned)
      .filter(([path, source]) => !allowed.has(path) && source.includes('admin'))
      .map(([path]) => path)
    expect(offenders).toEqual([])
    const probe = { 'src/views/portal/PortalHomeView.vue': "const roles = 'admin'" }
    expect(Object.entries(probe).filter(([path, source]) =>
      !allowed.has(path) && source.includes('admin')).length).toBe(1)
  })

  it('零订单 / 计费端点与假动作（3.7.3+ 才实现）', () => {
    const source = withoutComments(uiScanned())
    for (const endpoint of ['/orders', '/billing', '/bills', '/metering', '/payments', '/invoices']) {
      expect(source).not.toContain(endpoint)
    }
    // 反向探针
    expect('apiJson("/api/v1/orders")').toContain('/orders')
  })

  it('零数据本体 / 敏感原文（注释引述除外）', () => {
    for (const name of ['PortalHomeView.vue', 'PortalProductDetailView.vue']) {
      expect(hits(view(name), '本体')).toBe(0)
      expect(hits(view(name), '敏感原文')).toBe(0)
      expect(hits(view(name), '数据内容')).toBe(0)
    }
    expect(hits(withoutComments('<div class="data-body">数据本体</div>'), '本体')).toBe(1)
  })

  it('零服务端错误文案副本（同形常量除外，文案唯一源在 constants/catalog.ts）', () => {
    const source = withoutComments(uiScanned())
    expect(hits(source, '产品不存在或未在架')).toBe(0)
    expect(hits(source, '主体未入驻或不存在')).toBe(0)
    expect(hits(source, '无权限执行该操作')).toBe(0)
    expect(hits(source, '收藏或订阅记录不存在')).toBe(0)
    expect(hits(withoutComments(constantsSources['../../constants/catalog.ts'] ?? ''), PRODUCT_NOT_ACCESSIBLE_TIP))
      .toBeGreaterThan(0)
    // 反向探针：散写样本必被同一口径命中
    expect(hits(withoutComments("const tip = '产品不存在或未在架'"), '产品不存在或未在架')).toBe(1)
  })

  it('零定价 / 状态中文散写（经 constants/catalog.ts 收口）', () => {
    const source = withoutComments(uiScanned())
    for (const literal of ['免费', '按次', '包月', '交易额分成', '未上架', '已上架', '已下架', '已注销']) {
      expect(hits(source, literal)).toBe(0)
    }
    // 反向探针
    expect(hits(withoutComments("const label = '已上架'"), "'已上架'")).toBe(1)
  })

  it('首页仅调用读面：不得引入收藏 / 订阅写函数', () => {
    const source = view('PortalHomeView.vue')
    for (const writeFn of ['favoriteProduct', 'unfavoriteProduct', 'subscribeProduct', 'unsubscribeProduct']) {
      expect(source).not.toContain(writeFn)
    }
    expect(source).toContain('searchProducts')
    expect(source).toContain('listCategories')
  })

  it('关键常量与助手必须被视图引用（防文案 / 定价散写回流）', () => {
    expect(view('PortalHomeView.vue')).toContain('SEARCH_EMPTY_TIP')
    expect(view('PortalHomeView.vue')).toContain('pricingModelTagType')
    expect(view('PortalHomeView.vue')).toContain('PRICING_MODEL_LABELS')
    expect(view('PortalProductDetailView.vue')).toContain('PRODUCT_NOT_ACCESSIBLE_TIP')
    expect(view('PortalProductDetailView.vue')).toContain('formatPortalPricing')
    expect(view('PortalProductDetailView.vue')).toContain('PORTAL_ORDER_PLACEHOLDER_TIP')
  })

  it('api/catalog.ts 类型扩展：detail 加性 priceAmount: string | null（零新函数）', () => {
    const source = apiSources['../../api/catalog.ts'] ?? ''
    expect(source).toMatch(/priceAmount: string \| null/)
    expect(hits(source, 'export interface CatalogProductDetail')).toBe(1)
  })
})

describe('定价展示助手（§2.2 / §5）', () => {
  it('免费档显式"免费"（null / undefined 均回退到模型名）', () => {
    expect(formatPortalPricing('免费', null)).toBe('免费')
    expect(formatPortalPricing('免费', undefined)).toBe('免费')
  })

  it('付费档展示登记数值：按次 / 包月 / 交易额分成', () => {
    expect(formatPortalPricing('按次', '3.00')).toBe('按次（3.00）')
    expect(formatPortalPricing('包月', '19.90')).toBe('包月（19.90）')
    expect(formatPortalPricing('交易额分成', '5.00')).toBe('交易额分成（5.00）')
  })

  it('付费档无数值：仅模型名，不臆造空括号', () => {
    expect(formatPortalPricing('按次', null)).toBe('按次')
    expect(formatPortalPricing('按次', '')).toBe('按次')
  })

  it('定价 tag 色：免费 success，其余 info（按中文显示名反查枚举键）', () => {
    expect(pricingModelTagType('免费')).toBe('success')
    expect(pricingModelTagType('按次')).toBe('info')
    expect(pricingModelTagType('包月')).toBe('info')
    expect(pricingModelTagType('交易额分成')).toBe('info')
    expect(pricingModelTagType('未知档')).toBe('info')
  })

  it('门户文案常量：订购占位与页头说明逐字锚定', () => {
    expect(PORTAL_ORDER_PLACEHOLDER_TIP).toBe('订购与订单流程将在后续版本开放（WBS-3.7.3）')
    expect(PORTAL_BROWSE_DESC).toBe('浏览平台已上架的数据产品；订购与评价将在后续版本开放。')
  })
})
