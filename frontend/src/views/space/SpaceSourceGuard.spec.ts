import { describe, it, expect } from 'vitest'
import { SPACE_NOT_ACCESSIBLE_TIP } from '../../constants/space'

/**
 * 空间管理界面源集守卫（WBS-3.2.6 hifi §6.6 + §7 T29/T30，源集扫描可证伪）：
 * 以 Vite 原始文本导入（`?raw`）读取真实界面源文件后断言：
 * ① 空间视图源集无裸状态/角色/来源中文字面量（一律取 `constants/space.ts`）；
 * ② 空间视图与 API 模块不得直接 `fetch(`（必须经 `apiJson` 封装）；
 * ③ `platform.operator` 只出现在 `api/space.ts` 与 `stores/demoIdentity.ts`（最小权限面）；
 * ④ 非成员同形提示：不存在（1006C0004）与无权两条分支共用同一条提示常量与样式，
 *    页面源集不得散写该文案（反向探针：改文案 / 散写字面量必红）。
 */
const viewSources = import.meta.glob('./*.vue', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const apiSources = import.meta.glob('../../api/space.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const layoutSources = import.meta.glob('../../layouts/*.vue', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const routerSources = import.meta.glob('../../router/*.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const storeSources = import.meta.glob('../../stores/*.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const constantsSources = import.meta.glob('../../constants/*.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

/** 只统计实现源文件（测试文件自身不参与守卫扫描）。 */
const withoutSpecs = (sources: Record<string, string>): Record<string, string> =>
  Object.fromEntries(Object.entries(sources).filter(([path]) => !path.includes('.spec.')))

/** 界面源集（视图 + 布局 + 路由上下文），文案与 fetch 口径的作用域。 */
function uiScanned(): string {
  return [...Object.values(viewSources), ...Object.values(withoutSpecs(layoutSources)),
    ...Object.values(withoutSpecs(routerSources)), ...Object.values(withoutSpecs(apiSources))].join('\n')
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

describe('空间界面源集守卫（T29）', () => {
  it('扫描目标非空：四个空间视图与 api/space.ts 均被读入', () => {
    expect(Object.keys(viewSources).length).toBe(4)
    expect(Object.keys(apiSources).length).toBe(1)
  })

  it('空间视图与 API 模块不得直接 fetch（必须经 apiJson 封装）', () => {
    expect(hits(uiScanned(), 'fetch(')).toBe(0)
    // 反向探针：同一扫描口径对已知违规样本必命中
    expect(hits('const r = await fetch(url)', 'fetch(')).toBe(1)
  })

  it('状态 / 角色 / 来源标注中文一律经 constants/space.ts 收口，页面不得散写', () => {
    const source = withoutComments(uiScanned())
    // 状态：已创建（未启用）/ 已启用 / 已冻结 / 已解散
    // 角色：所有者 / 管理员 / 成员（含授予、收回管理员）
    // 准入形态与状态：申请 / 邀请 / 待审批 / 待确认 / 已通过 / 已拒绝 / 已谢绝 / 已取消
    // 来源三态：继承自平台 / 空间级生效 / 空间覆盖未生效（取严）
    const bareLiterals = ["'已启用'", '"已启用"', "'已冻结'", '"已冻结"', "'已解散'", '"已解散"',
      "'已创建（未启用）'", '"已创建（未启用）"', "'所有者'", '"所有者"', "'管理员'", '"管理员"',
      "'待审批'", '"待审批"', "'待确认'", '"待确认"', "'已通过'", '"已通过"', "'已拒绝'", '"已拒绝"',
      "'已谢绝'", '"已谢绝"', "'已取消'", '"已取消"', "'继承自平台'", '"继承自平台"',
      "'空间级生效'", '"空间级生效"', "'空间覆盖未生效（取严）'", '"空间覆盖未生效（取严）"']
    for (const literal of bareLiterals) {
      expect(hits(source, literal)).toBe(0)
    }
    // 反向探针：同一扫描口径对散写样本必命中（证明断言非空转）
    expect(hits(withoutComments("const label = '已启用'"), "'已启用'")).toBe(1)
    expect(hits(withoutComments('const role = "所有者"'), '"所有者"')).toBe(1)
    // 注释引述不误判（剥离生效）
    expect(hits(withoutComments('// 状态"已冻结"不可提交'), '"已冻结"')).toBe(0)
  })

  it('platform.operator 只出现在 api/space.ts 与 stores/demoIdentity.ts（最小权限面）', () => {
    const allowed = new Set(Object.keys(apiSources))
    const identity = Object.keys(withoutSpecs(storeSources)).filter((path) => path.endsWith('demoIdentity.ts'))
    const scanned = { ...withoutSpecs(storeSources), ...withoutSpecs(layoutSources),
      ...withoutSpecs(routerSources), ...withoutSpecs(constantsSources) }
    const offenders: string[] = []
    for (const [path, source] of Object.entries(scanned)) {
      if ((allowed.has(path) || identity.includes(path))) continue
      if (source.includes('platform.operator')) offenders.push(path)
    }
    expect(offenders).toEqual([])
    // 反向探针：越界样本必被同一口径判违规
    const probe = { 'src/views/space/ListView.vue': "const roles = 'applicant,platform.operator'" }
    expect(Object.entries(probe).filter(([path, source]) =>
      !allowed.has(path) && source.includes('platform.operator')).length).toBe(1)
  })
})

describe('非成员同形提示守卫（T30）', () => {
  it('提示常量 = 唯一一条统一文案（反向探针：改文案必红）', () => {
    expect(SPACE_NOT_ACCESSIBLE_TIP).toBe('空间不存在或无权访问')
  })

  it('页面源集不得散写"不存在 / 无权"文案，一律引用共享常量', () => {
    const source = withoutComments(uiScanned())
    expect(hits(source, '空间不存在或无权访问')).toBe(0)
    // 详情页必须引用共享常量（不存在分支与无权分支同一文案与样式）：样本必命中（S3 收口强断言）
    const detailSource = withoutComments(viewSources['./DetailView.vue'])
    expect(hits(detailSource, ':title="SPACE_NOT_ACCESSIBLE_TIP"')).toBe(1)
    expect(hits(detailSource, 'SPACE_NOT_ACCESSIBLE_CODES')).toBeGreaterThan(0)
    // 反向探针：散写样本必被同一口径命中；未引用常量的样本必不命中（证明该断言非空转）
    expect(hits(withoutComments("const tip = '空间不存在或无权访问'"), '空间不存在或无权访问')).toBe(1)
    expect(hits(withoutComments('const tip = notAccessibleTip'), ':title="SPACE_NOT_ACCESSIBLE_TIP"')).toBe(0)
    expect(hits(withoutComments('const codes = notAccessibleCodes'), 'SPACE_NOT_ACCESSIBLE_CODES')).toBe(0)
  })

  it('不得出现第二套"无权"文案（同形口径只有一条提示）', () => {
    const source = withoutComments(uiScanned())
    expect(hits(source, '无权访问该空间')).toBe(0)
    expect(hits(source, '空间不存在或您无权访问')).toBe(0)
  })
})
