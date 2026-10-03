import { describe, it, expect } from 'vitest'
import {
  PRODUCT_NOT_ACCESSIBLE_TIP,
  RESOURCE_NOT_ACCESSIBLE_TIP,
  PRODUCT_MANAGE_NOT_FOUND_TIP,
} from '../../constants/catalog'

/**
 * 目录界面源集守卫（WBS-3.3.6 hifi §6.7 + §7 T19 / T17 源集锚，源集扫描可证伪；沿 3.2.6 SpaceSourceGuard 先例）：
 * 以 Vite 原始文本导入（`?raw`）读取真实界面源文件后断言：
 * ① 目录视图源集无裸状态/形态/定价/类型/留痕动作中文字面量（一律取 `constants/catalog.ts`）；
 * ② 目录视图与 API 模块不得直接 `fetch(`（必须经 `apiJson` 封装）；
 * ③ **'admin' 角色头字面量仅限 `stores/demoIdentity.ts` 与 `api/catalog.ts`**（T17 源集锚——
 *    普通档请求携带 admin 会让治理类与"越权被拒"类剧本步骤被放行，让判定失真）；
 * ④ 同形提示三条（1007C0005 / 1007C0011 / 1007C0012）各自唯一常量、值与后端文案逐字一致，
 *    页面源集不得散写（反向探针：改文案 / 散写字面量必红）；
 * ⑤ 详情页无数据本体段落（源集连"本体"引述都只允许出现在注释中）。
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

/** 只统计实现源文件（测试文件自身不参与守卫扫描）。 */
const withoutSpecs = (sources: Record<string, string>): Record<string, string> =>
  Object.fromEntries(Object.entries(sources).filter(([path]) => !path.includes('.spec.')))

/** 界面源集（五个视图 + api/catalog.ts），文案与 fetch 口径的作用域。 */
function uiScanned(): string {
  return [...Object.values(withoutSpecs(viewSources)), ...Object.values(withoutSpecs(apiSources))].join('\n')
}

function hits(source: string, pattern: string): number {
  return source.split(pattern).length - 1
}

/**
 * 剥离注释后的源码（块注释 / 行注释 / 模板注释）：注释中对状态的中文引述不是"页面散写文案"。
 */
function withoutComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
}

describe('目录界面源集守卫（T19）', () => {
  it('扫描目标非空：五个目录视图与 api/catalog.ts 均被读入', () => {
    const vuePaths = Object.keys(withoutSpecs(viewSources))
    expect(vuePaths.length).toBe(5)
    // PortalView 替换占位 IndexView（死代码禁令），占位页不得残留
    expect(vuePaths.some((p) => p.includes('IndexView'))).toBe(false)
    expect(Object.keys(withoutSpecs(apiSources)).length).toBe(1)
  })

  it('目录视图与 API 模块不得直接 fetch（必须经 apiJson 封装）', () => {
    expect(hits(uiScanned(), 'fetch(')).toBe(0)
    // 反向探针：同一扫描口径对已知违规样本必命中
    expect(hits('const r = await fetch(url)', 'fetch(')).toBe(1)
  })

  it('状态 / 形态 / 定价 / 类型 / 留痕动作中文一律经 constants/catalog.ts 收口，页面不得散写', () => {
    const source = withoutComments(uiScanned())
    // 产品状态四态 / 资源状态两态
    // 形态四类（API 为非中文码，不入清单）与定价四档
    // 长动作标签（短词"收藏/订阅/上架时间"等列头文案除外——常量引用由视图 import 面承载）
    const bareLiterals = ['未上架', '已上架', '已下架', '已注销', '生效中',
      '数据集', '报告',
      '免费', '按次', '包月', '交易额分成',
      '取消收藏', '重新上架', '强制下架', '治理查看']
    for (const literal of bareLiterals) {
      expect(hits(source, literal)).toBe(0)
    }
    // "模型"仅在"定价模型"列头复合词中出现（列名来自 hifi §6.2），裸用为违规
    expect(hits(source, '模型')).toBe(hits(source, '定价模型'))
    // 反向探针：同一扫描口径对散写样本必命中（证明断言非空转）
    expect(hits(withoutComments("const label = '已上架'"), "'已上架'")).toBe(1)
    expect(hits(withoutComments('const type = "数据集"'), '"数据集"')).toBe(1)
    // 注释引述不误判（剥离生效）
    expect(hits(withoutComments('// 状态"已下架"不可再上架'), '"已下架"')).toBe(0)
  })

  it("'admin' 角色头字面量仅限 demoIdentity.ts 与 api/catalog.ts（T17 源集锚）", () => {
    const allowed = new Set([...Object.keys(withoutSpecs(apiSources)), ...Object.keys(identitySources)])
    const scanned = { ...withoutSpecs(viewSources), ...withoutSpecs(constantsSources) }
    const offenders = Object.entries(scanned)
      .filter(([path, source]) => !allowed.has(path) && source.includes('admin'))
      .map(([path]) => path)
    expect(offenders).toEqual([])
    // 反向探针：越界样本必被同一口径判违规
    const probe = { 'src/views/catalog/GovernanceView.vue': "const roles = 'admin'" }
    expect(Object.entries(probe).filter(([path, source]) =>
      !allowed.has(path) && source.includes('admin')).length).toBe(1)
  })
})

describe('同形提示守卫（T19 / §6.7）', () => {
  it('三条同形提示常量 = 唯一一条统一文案（与后端文案逐字一致；反向探针：改文案必红）', () => {
    expect(RESOURCE_NOT_ACCESSIBLE_TIP).toBe('资源不存在或无权访问')
    expect(PRODUCT_NOT_ACCESSIBLE_TIP).toBe('产品不存在或未在架')
    expect(PRODUCT_MANAGE_NOT_FOUND_TIP).toBe('产品不存在或无权操作')
  })

  it('页面源集不得散写同形文案，一律引用共享常量', () => {
    const source = withoutComments(uiScanned())
    expect(hits(source, '资源不存在或无权访问')).toBe(0)
    expect(hits(source, '产品不存在或未在架')).toBe(0)
    expect(hits(source, '产品不存在或无权操作')).toBe(0)
    // 详情页与资源页必须引用共享常量（同形分支共用同一文案与样式）：样本必命中
    const detailSource = withoutComments(viewSources['./ProductDetailView.vue'] ?? '')
    expect(hits(detailSource, 'PRODUCT_NOT_ACCESSIBLE_TIP')).toBeGreaterThan(0)
    // 反向探针：散写样本必被同一口径命中；未引用常量的样本必不命中（证明该断言非空转）
    expect(hits(withoutComments("const tip = '产品不存在或未在架'"), '产品不存在或未在架')).toBe(1)
    expect(hits(withoutComments('const tip = notAccessibleTip'), 'PRODUCT_NOT_ACCESSIBLE_TIP')).toBe(0)
  })

  it('不得出现第二套同形文案（同形口径各只有一条提示）', () => {
    const source = withoutComments(uiScanned())
    expect(hits(source, '资源不存在或您无权访问')).toBe(0)
    expect(hits(source, '产品不存在或已下架')).toBe(0)
    expect(hits(source, '产品不存在或无权查看')).toBe(0)
  })
})

describe('详情页数据本体零接触守卫（T6 源集面）', () => {
  it('产品详情页源码无数据本体段落（"本体"只允许出现在注释引述中）', () => {
    const detailSource = withoutComments(viewSources['./ProductDetailView.vue'] ?? '')
    expect(detailSource).not.toBe('')
    expect(hits(detailSource, '本体')).toBe(0)
    // 反向探针：出现本体段落结构的样本必被同一口径命中
    expect(hits(withoutComments('<div class="data-body">数据本体</div>'), '本体')).toBe(1)
  })
})
