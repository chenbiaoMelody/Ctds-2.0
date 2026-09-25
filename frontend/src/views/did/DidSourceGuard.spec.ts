import { describe, it, expect } from 'vitest'

/**
 * 防回退守卫（WBS-3.1.11 hifi T17①②，源集扫描可证伪）：
 * 以 Vite 原始文本导入（`?raw`）读取真实界面源文件后断言：
 * ① DID 界面源集必须经 apiJson 封装（不得直接 fetch）；
 * ② 不得出现吊销回滚类动作字样（规格行为 4 规则 4：吊销不可逆、无回滚入口）与私钥字样（红线 7）。
 * 反向探针：同一扫描口径对已知违规样本必命中，证明断言非空转。
 */
const viewSources = import.meta.glob('./*.vue', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const apiSources = import.meta.glob('../../api/did.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

function scanned(): string {
  return [...Object.values(viewSources), ...Object.values(apiSources)].join('\n')
}

function hits(source: string, pattern: string): number {
  return source.split(pattern).length - 1
}

/**
 * 剥离注释后的源码（块注释 / 行注释 / 模板注释）。
 * 注释中对状态的正常中文引述（如 `"待签发"记录为空`）不是"页面散写文案"，不应误判；
 * 只有真实代码/模板里的字面量才算违规。
 */
function withoutComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
}

describe('DID 界面源集守卫（WBS-3.1.11 T17）', () => {
  it('扫描目标非空：DID 两个视图与 API 模块均被读入', () => {
    expect(Object.keys(viewSources).length).toBe(2)
    expect(Object.keys(apiSources).length).toBe(1)
  })

  it('界面与 API 模块不得直接调用 fetch（必须经 apiJson 封装）', () => {
    expect(hits(scanned(), 'fetch(')).toBe(0)
    // 反向探针：同一扫描口径对已知含违规字样的样本必命中
    expect(hits('const r = await fetch(url)', 'fetch(')).toBe(1)
  })

  it('界面不得出现吊销回滚类动作（吊销不可逆）', () => {
    const source = scanned()
    expect(hits(source, '恢复')).toBe(0)
    expect(hits(source, 'restore')).toBe(0)
    expect(hits(source, 'unrevoke')).toBe(0)
  })

  it('界面不得出现私钥字样（只见 KMS 密钥引用与公开要素）', () => {
    expect(hits(scanned(), 'privateKey')).toBe(0)
    expect(hits(scanned(), 'PrivateKey')).toBe(0)
    // 反向探针
    expect(hits('const privateKey = "x"', 'privateKey')).toBe(1)
  })

  it('状态文案必须经 constants/did.ts 收口：页面不得散写状态中文字面量（hifi §6.4）', () => {
    const source = withoutComments(scanned())
    // 判定口径 = 代码/模板中"作为独立字符串字面量"的中文状态词
    // （含引号，故 `'已吊销该 DID'` 之类提示语不误判；注释已剥离，故注释引述也不误判）
    for (const literal of ["'有效'", "'已吊销'", "'待签发'", '"有效"', '"已吊销"', '"待签发"']) {
      expect(hits(source, literal)).toBe(0)
    }
    // 反向探针：同一扫描口径对散写样本必命中（证明断言非空转）
    expect(hits(withoutComments("const label = '待签发'"), "'待签发'")).toBe(1)
    expect(hits(withoutComments('const l = "有效"'), '"有效"')).toBe(1)
    // 注释引述不误判（剥离生效）
    expect(hits(withoutComments('// "待签发" 记录为空'), '"待签发"')).toBe(0)
  })
})
