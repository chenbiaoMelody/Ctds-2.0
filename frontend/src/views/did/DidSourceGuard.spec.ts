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
})
