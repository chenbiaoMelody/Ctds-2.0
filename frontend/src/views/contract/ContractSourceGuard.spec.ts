import { describe, it, expect } from 'vitest'
import {
  TEMPLATE_NOT_ACCESSIBLE_TIP,
  TEMPLATE_MAINTAIN_FORBIDDEN_TIP,
  CONTRACT_NOT_ACCESSIBLE_TIP,
  PRODUCT_NOT_DEALABLE_TIP,
  TEMPLATE_NOT_INITIABLE_TIP,
  ADMISSION_REQUIRED_BROWSE_TIP,
  ADMISSION_REQUIRED_INITIATE_TIP,
  ADMISSION_REQUIRED_MAINTAIN_TIP,
  SIMULATION_HONESTY_TIP,
  EXECUTION_DEMO_TIP,
} from '../../constants/contract'

/**
 * 合约界面源集守卫（WBS-3.4.7 hifi §6.6 + §7 T20，源集扫描可证伪；沿 3.3.6 CatalogSourceGuard
 * 先例）：以 Vite 原始文本导入（`?raw`）读取真实界面源文件后断言：
 * ① 合约视图与共享组件源集无裸状态/类型/动作码/要素名中文字面量（一律取 `constants/contract.ts`）；
 * ② 合约视图与 API 模块不得直接 `fetch(`（必须经 `apiJson` 封装）；
 * ③ **'admin' 角色头字面量仅限 `stores/demoIdentity.ts` 与 `api/contract.ts`**（T18 源集锚——
 *    普通档请求携带 admin 会让模板维护类与"越权被拒"类剧本步骤被放行，让判定失真）；
 * ④ 同形提示常量与后端文案逐字一致，页面源集不得散写（反向探针：改文案 / 散写字面量必红）；
 * ⑤ 详情页无数据本体段落。
 */
const viewSources = import.meta.glob('./*.vue', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const componentSources = import.meta.glob('./components/*.vue', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const apiSources = import.meta.glob('../../api/contract.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const constantsSources = import.meta.glob('../../constants/contract.ts', {
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

/** 界面源集（四视图 + 两共享组件 + api/contract.ts），文案与 fetch 口径的作用域。 */
function uiScanned(): string {
  return [
    ...Object.values(withoutSpecs(viewSources)),
    ...Object.values(withoutSpecs(componentSources)),
    ...Object.values(withoutSpecs(apiSources)),
  ].join('\n')
}

function hits(source: string, pattern: string): number {
  return source.split(pattern).length - 1
}

/** 剥离注释后的源码：注释中对状态的中文引述不是"页面散写文案"。 */
function withoutComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
}

describe('合约界面源集守卫（T20）', () => {
  it('扫描目标非空：四个合约视图、两个共享组件与 api/contract.ts 均被读入', () => {
    const vuePaths = Object.keys(withoutSpecs(viewSources))
    expect(vuePaths.length).toBe(4)
    expect(vuePaths.some((p) => p.includes('TemplateView'))).toBe(true)
    expect(vuePaths.some((p) => p.includes('WorkbenchView'))).toBe(true)
    expect(vuePaths.some((p) => p.includes('ContractDetailView'))).toBe(true)
    expect(vuePaths.some((p) => p.includes('GovernanceView'))).toBe(true)
    expect(Object.keys(withoutSpecs(componentSources)).length).toBe(2)
    expect(Object.keys(withoutSpecs(apiSources)).length).toBe(1)
  })

  it('合约视图与 API 模块不得直接 fetch（必须经 apiJson 封装）', () => {
    expect(hits(uiScanned(), 'fetch(')).toBe(0)
    // 反向探针：同一扫描口径对已知违规样本必命中
    expect(hits('const r = await fetch(url)', 'fetch(')).toBe(1)
  })

  it('状态 / 类型 / 动作码 / 要素名中文一律经 constants/contract.ts 收口，页面不得散写', () => {
    const source = withoutComments(uiScanned())
    // 合约六态 / 模板两态 / 我的角色 / 留痕动作 / 策略要素（页面散写即违规；
    // "用途"因输入框占位语义高频出现不设清单锚，"域内范围"特异可锚——评审③ P3）
    const bareLiterals = ['协商中', '待签署', '部分签署', '已生效', '已完结', '已终止',
      '启用中', '已停用', '提供方', '需求方',
      '治理查看', '越权拒绝', '验签异常',
      '次数上限', '使用期限', '禁止再分发', '域内范围']
    for (const literal of bareLiterals) {
      expect(hits(source, literal)).toBe(0)
    }
    // 反向探针：同一扫描口径对散写样本必命中（证明断言非空转）
    expect(hits(withoutComments("const label = '已生效'"), "'已生效'")).toBe(1)
    expect(hits(withoutComments("const t = '协商中'"), "'协商中'")).toBe(1)
    // 注释引述不误判（剥离生效）
    expect(hits(withoutComments('// 状态"已生效"后无单方撤回'), '"已生效"')).toBe(0)
  })

  it("'admin' 角色头字面量仅限 demoIdentity.ts 与 api/contract.ts（T18 源集锚）", () => {
    const allowed = new Set([...Object.keys(withoutSpecs(apiSources)), ...Object.keys(identitySources)])
    const scanned = { ...withoutSpecs(viewSources), ...withoutSpecs(componentSources),
      ...withoutSpecs(constantsSources) }
    const offenders = Object.entries(scanned)
      .filter(([path, source]) => !allowed.has(path) && source.includes('admin'))
      .map(([path]) => path)
    expect(offenders).toEqual([])
    // 反向探针：越界样本必被同一口径判违规
    const probe = { 'src/views/contract/GovernanceView.vue': "const roles = 'admin'" }
    expect(Object.entries(probe).filter(([path, source]) =>
      !allowed.has(path) && source.includes('admin')).length).toBe(1)
  })
})

describe('同形提示守卫（T20 / §6.6）', () => {
  it('同形提示常量 = 唯一一条统一文案（与后端文案逐字一致；反向探针：改文案必红）', () => {
    expect(TEMPLATE_NOT_ACCESSIBLE_TIP).toBe('模板不存在或不可用')
    expect(TEMPLATE_MAINTAIN_FORBIDDEN_TIP).toBe('无权进行模板维护操作')
    expect(CONTRACT_NOT_ACCESSIBLE_TIP).toBe('合约不存在或不可见')
    expect(PRODUCT_NOT_DEALABLE_TIP).toBe('产品不存在或未在架，无法发起合约')
    expect(TEMPLATE_NOT_INITIABLE_TIP).toBe('所选模板不可用于发起合约')
    expect(ADMISSION_REQUIRED_BROWSE_TIP).toBe('主体未入驻或不存在，无法浏览模板')
    expect(ADMISSION_REQUIRED_INITIATE_TIP).toBe('主体未入驻或不存在，无法发起合约')
    expect(ADMISSION_REQUIRED_MAINTAIN_TIP).toBe('主体未入驻或不存在，无法维护模板')
  })

  it('诚实边界两则文案逐字（§6.6-7：常驻策略与使用区，不折叠隐藏）', () => {
    expect(SIMULATION_HONESTY_TIP).toBe('模拟结论不是放行承诺；正式放行以真实执行通道为准')
    expect(EXECUTION_DEMO_TIP).toBe('受控执行为演示期受控入口，正式交付链接入后同判定面')
  })

  it('页面源集不得散写同形文案，一律引用共享常量', () => {
    const source = withoutComments(uiScanned())
    expect(hits(source, '模板不存在或不可用')).toBe(0)
    expect(hits(source, '合约不存在或不可见')).toBe(0)
    expect(hits(source, '产品不存在或未在架，无法发起合约')).toBe(0)
    // 详情页与模板页必须引用共享常量（同形分支共用同一文案与样式）：样本必命中
    const detailSource = withoutComments(viewSources['./ContractDetailView.vue'] ?? '')
    expect(hits(detailSource, 'CONTRACT_NOT_ACCESSIBLE_TIP')).toBeGreaterThan(0)
    const templateSource = withoutComments(viewSources['./TemplateView.vue'] ?? '')
    expect(hits(templateSource, 'TEMPLATE_NOT_ACCESSIBLE_TIP')).toBeGreaterThan(0)
    // 反向探针：散写样本必被同一口径命中；引用常量的样本必不命中（证明该断言非空转）
    expect(hits(withoutComments("const tip = '合约不存在或不可见'"), '合约不存在或不可见')).toBe(1)
    expect(hits(withoutComments('const tip = notAccessibleTip'), 'CONTRACT_NOT_ACCESSIBLE_TIP')).toBe(0)
  })

  it('不得出现第二套同形文案（同形口径各只有一条提示）', () => {
    const source = withoutComments(uiScanned())
    expect(hits(source, '模板不存在或已停用')).toBe(0)
    expect(hits(source, '合约不存在或您无权访问')).toBe(0)
    expect(hits(source, '产品不存在或已下架')).toBe(0)
  })
})

describe('详情页数据本体零接触守卫（§6.4-B 源集面）', () => {
  it('合约详情页源码无数据本体段落（"本体"只允许出现在注释引述中）', () => {
    const detailSource = withoutComments(viewSources['./ContractDetailView.vue'] ?? '')
    expect(detailSource).not.toBe('')
    expect(hits(detailSource, '本体')).toBe(0)
    // 反向探针：出现本体段落结构的样本必被同一口径命中
    expect(hits(withoutComments('<div class="data-body">数据本体</div>'), '本体')).toBe(1)
  })
})
