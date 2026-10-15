// 双源守卫真读后端源文件需要 node 内置模块类型（node:fs / node:path / node:url）——
// tsconfig.app.json 的 types 白名单只含 vite/client，用三斜杠引用按文件引入（零配置改动）。
/// <reference types="node" />
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
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
 * ⑤ 详情页无数据本体段落；
 * ⑥ **同形文案双源守卫（DB-43）**：前端 8 条 *_TIP 是后端 `ContractErrorCodes.java` 8 条
 *    *_MESSAGE 的副本（后端为真源）——真读后端源文件逐字比对，消灭"人工比对"依赖
 *    （后端改了前端没跟 → 红；映射表人工维护 8 对，新增同形文案须同步扩表）。
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

/** 界面源集（四视图 + 三共享组件 + api/contract.ts），文案与 fetch 口径的作用域（DB-44：新增只读五分区共享组件）。 */
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
  it('扫描目标非空：四个合约视图、三个共享组件与 api/contract.ts 均被读入', () => {
    const vuePaths = Object.keys(withoutSpecs(viewSources))
    expect(vuePaths.length).toBe(4)
    expect(vuePaths.some((p) => p.includes('TemplateView'))).toBe(true)
    expect(vuePaths.some((p) => p.includes('WorkbenchView'))).toBe(true)
    expect(vuePaths.some((p) => p.includes('ContractDetailView'))).toBe(true)
    expect(vuePaths.some((p) => p.includes('GovernanceView'))).toBe(true)
    const componentPaths = Object.keys(withoutSpecs(componentSources))
    expect(componentPaths.length).toBe(3)
    expect(componentPaths.some((p) => p.includes('ClauseEditor'))).toBe(true)
    expect(componentPaths.some((p) => p.includes('StrategyEditor'))).toBe(true)
    expect(componentPaths.some((p) => p.includes('ContractReadOnlySections'))).toBe(true)
    expect(Object.keys(withoutSpecs(apiSources)).length).toBe(1)
  })

  it('只读五分区单点：策略行渲染仅存于共享组件，两视图均引用之（DB-44）', () => {
    const views = withoutSpecs(viewSources)
    const sections = withoutSpecs(componentSources)['./components/ContractReadOnlySections.vue'] ?? ''
    expect(sections).not.toBe('')
    // 视图内不再定义策略行渲染（收敛单点——分散定义即违规）
    for (const source of Object.values(views)) {
      expect(hits(source, 'function strategyLine')).toBe(0)
    }
    expect(hits(sections, 'function strategyLine')).toBe(1)
    // 两视图均须引用共享组件（抽了组件却不用 = 复制仍在 → 必红）
    expect(hits(views['./ContractDetailView.vue'] ?? '', 'ContractReadOnlySections')).toBeGreaterThan(0)
    expect(hits(views['./GovernanceView.vue'] ?? '', 'ContractReadOnlySections')).toBeGreaterThan(0)
    // 反向探针：散写样本必被同一口径命中
    expect(hits('function strategyLine(el: string) { return el }', 'function strategyLine')).toBe(1)
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

// ---- 同形文案双源守卫（DB-43：真读后端真源，消灭"人工比对"依赖）----
// 前端 constants/contract.ts 的 8 条 *_TIP 是后端 ContractErrorCodes.java 的 8 条 *_MESSAGE
// 的副本（后端为文案真源，该文件 Javadoc："对外文案为服务端常量"）。既有守卫比的是"测试内
// 第三份字面量"；本块真读后端源文件逐字比对：后端改了前端没跟 → 门禁前端测试红灯。
// 映射表为人工维护 8 对：后端新增同形对外文案且前端建副本时须同步扩表（hifi §2.3 规则）。
const TIP_TO_MESSAGE: Array<[string, string]> = [
  ['TEMPLATE_NOT_ACCESSIBLE_TIP', 'TEMPLATE_NOT_FOUND_OR_UNAVAILABLE_MESSAGE'],
  ['TEMPLATE_MAINTAIN_FORBIDDEN_TIP', 'TEMPLATE_FORBIDDEN_MESSAGE'],
  ['ADMISSION_REQUIRED_BROWSE_TIP', 'BROWSE_ADMISSION_REQUIRED_MESSAGE'],
  ['ADMISSION_REQUIRED_INITIATE_TIP', 'INITIATE_ADMISSION_REQUIRED_MESSAGE'],
  ['ADMISSION_REQUIRED_MAINTAIN_TIP', 'MAINTAIN_ADMISSION_REQUIRED_MESSAGE'],
  ['CONTRACT_NOT_ACCESSIBLE_TIP', 'CONTRACT_NOT_VISIBLE_MESSAGE'],
  ['PRODUCT_NOT_DEALABLE_TIP', 'PRODUCT_NOT_AVAILABLE_FOR_DEAL_MESSAGE'],
  ['TEMPLATE_NOT_INITIABLE_TIP', 'TEMPLATE_NOT_AVAILABLE_FOR_INITIATION_MESSAGE'],
]

/** 解析前端常量源文本：export const X_TIP = '...'（checkstyle 无对应面，正则只认规整单行形态）。 */
function parseExportedTips(source: string): Record<string, string> {
  const out: Record<string, string> = {}
  const re = /export const (\w+_TIP) = '([^']*)'/g
  let m: RegExpExecArray | null
  while ((m = re.exec(source)) !== null) {
    out[m[1]] = m[2]
  }
  return out
}

/** 解析后端码表源文本：public static final String X_MESSAGE = "...";（单行形态由 checkstyle 保证）。 */
function parseStaticMessages(source: string): Record<string, string> {
  const out: Record<string, string> = {}
  const re = /public static final String (\w+_MESSAGE) = "([^"]*)";/g
  let m: RegExpExecArray | null
  while ((m = re.exec(source)) !== null) {
    out[m[1]] = m[2]
  }
  return out
}

/** 纯函数：按映射表比对两册文案，产出差异清单（供断言与反向探针复用）。 */
function diffTips(
  tips: Record<string, string>,
  messages: Record<string, string>,
  mapping: Array<[string, string]>,
): string[] {
  const diffs: string[] = []
  for (const [tip, message] of mapping) {
    const tipValue = tips[tip]
    const messageValue = messages[message]
    if (tipValue === undefined) {
      diffs.push(tip + ' (前端副本缺失)')
    } else if (messageValue === undefined) {
      diffs.push(message + ' (后端真源缺失)')
    } else if (tipValue !== messageValue) {
      diffs.push(tip + '=' + JSON.stringify(tipValue) + ' vs ' + message + '=' + JSON.stringify(messageValue))
    }
  }
  return diffs
}

describe('同形文案双源守卫（DB-43：前端副本 vs 后端真源）', () => {
  const frontendConstants = Object.values(constantsSources)[0] ?? ''
  // 跨端读取实现说明（hifi §2.3 实施澄清 E2）：路径构造用 fileURLToPath(import.meta.url) +
  // path.resolve（相对本 spec 文件定位，不依赖 cwd）。勿用 new URL(rel, import.meta.url)——
  // 该形态会被 Vite 的"资产 URL"改写劫持成 dev-server URL（http://localhost:3000/@fs/...，
  // 本卡实测），readFileSync 拒绝非 file scheme；import.meta.url 直接取值为 file:// URL
  // （vitest 实测）。找不到文件时抛错 = 本守卫红（fail-visible，绝不静默跳过）。
  function backendSource(): string {
    const here = dirname(fileURLToPath(import.meta.url))
    return readFileSync(
      resolve(here, '../../../../services/contract-service/src/main/java/com/ctds/contract/domain/ContractErrorCodes.java'),
      'utf8',
    )
  }

  it('双源均可解析：后端 8 条映射常量全部解析到位（防解析失效 / 常量改名后静默空转）', () => {
    expect(frontendConstants).not.toBe('')
    const backendErrorCodes = backendSource()
    expect(backendErrorCodes).not.toBe('')
    const messages = parseStaticMessages(backendErrorCodes)
    for (const [, message] of TIP_TO_MESSAGE) {
      expect(Object.keys(messages)).toContain(message)
    }
    const tips = parseExportedTips(frontendConstants)
    for (const [tip] of TIP_TO_MESSAGE) {
      expect(Object.keys(tips)).toContain(tip)
    }
  })

  it('前端副本与后端真源 8 对逐字一致（漂移即红，失败信息给出常量名与两侧取值）', () => {
    const diffs = diffTips(parseExportedTips(frontendConstants), parseStaticMessages(backendSource()), TIP_TO_MESSAGE)
    expect(diffs).toEqual([])
  })

  it('映射表完备锚：恰 8 对（防悄悄删映射；新增同形文案必须同步扩表）', () => {
    expect(TIP_TO_MESSAGE.length).toBe(8)
  })

  it('反向探针：diffTips 对漂移样本必产出差异、对一致样本必产出空（证明比对逻辑非空转）', () => {
    const tips = { A_TIP: '甲', B_TIP: '乙' }
    const messages = { A_MESSAGE: '甲', B_MESSAGE: '乙二' }
    const mapping: Array<[string, string]> = [['A_TIP', 'A_MESSAGE'], ['B_TIP', 'B_MESSAGE']]
    // 漂移样本：B 两侧取值不同 -> 必产出差异
    expect(diffTips(tips, messages, mapping)).toEqual(['B_TIP="乙" vs B_MESSAGE="乙二"'])
    // 缺失样本：常量改名 -> 必指出缺失侧
    expect(diffTips(tips, messages, [['A_TIP', 'C_MESSAGE']])).toEqual(['C_MESSAGE (后端真源缺失)'])
    // 一致样本 -> 必产出空
    expect(diffTips(tips, messages, [['A_TIP', 'A_MESSAGE']])).toEqual([])
  })
})
