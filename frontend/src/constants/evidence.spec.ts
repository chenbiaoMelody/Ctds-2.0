import { describe, it, expect } from 'vitest'
import {
  EVIDENCE_STAGE_LABELS,
  EVIDENCE_VERIFY_CONCLUSION_LABELS,
  isBeyond90Days,
  labelOf,
  orDash,
  truncateDigest,
} from './evidence'

/**
 * 存证域常量层纯函数用例（WBS-3.8.5 评审④ F-5 补齐；hifi §4 通用契约）：
 * - labelOf 未知枚举兜底（原样显示，不隐藏不猜测）与空值占位；
 * - truncateDigest 中截断（前 8 + … + 后 8，≤24 位原样）；
 * - isBeyond90Days 90 天窗口引导判定（Q6-A 体验引导纯 UI，业务裁决在后端）。
 */
describe('labelOf / orDash 兜底（评审④ F-5）', () => {
  it('labelOf：命中标签、未知枚举原样、空值占位', () => {
    expect(labelOf(EVIDENCE_STAGE_LABELS, 'DELIVERY')).toBe('交付')
    expect(labelOf(EVIDENCE_STAGE_LABELS, 'FUTURE_STAGE')).toBe('FUTURE_STAGE')
    expect(labelOf(EVIDENCE_VERIFY_CONCLUSION_LABELS, '')).toBe('—')
    expect(labelOf(EVIDENCE_VERIFY_CONCLUSION_LABELS, null)).toBe('—')
    expect(labelOf(EVIDENCE_VERIFY_CONCLUSION_LABELS, undefined)).toBe('—')
  })

  it('orDash：有值透传（含 0 与字符串），空值占位', () => {
    expect(orDash('LTX000000000004')).toBe('LTX000000000004')
    expect(orDash(0)).toBe(0)
    expect(orDash('')).toBe('—')
    expect(orDash(null)).toBe('—')
    expect(orDash(undefined)).toBe('—')
  })
})

describe('truncateDigest 中截断（hifi §4 通用契约）', () => {
  it('长摘要（64 位 hex）= 前 8 + … + 后 8', () => {
    const digest = 'dffaa2a5ad0c7030c293c7c608a9d2b7980e40ceb0c3a6a1851ced90db9ab4f5'
    expect(truncateDigest(digest)).toBe('dffaa2a5…db9ab4f5')
  })

  it('短值（≤24 位）原样；空值占位', () => {
    expect(truncateDigest('LTX000000000004')).toBe('LTX000000000004')
    expect(truncateDigest('aaa111')).toBe('aaa111')
    expect(truncateDigest('')).toBe('')
    expect(truncateDigest(null)).toBe('—')
    expect(truncateDigest(undefined)).toBe('—')
  })
})

describe('isBeyond90Days 窗口引导（Q6-A 纯 UI 判定）', () => {
  const day0 = Date.UTC(2026, 9, 1) // 2026-10-01
  const dayMs = 24 * 60 * 60 * 1000

  it('未设首选日（null）→ 全部放行（disabled-date 不拦截首点）', () => {
    expect(isBeyond90Days(null, day0)).toBe(false)
    expect(isBeyond90Days(null, day0 + 200 * dayMs)).toBe(false)
  })

  it('跨度 ≤90 天放行、>90 天禁选、恰 90 天放行（边界含等号语义 = 不得超过）', () => {
    expect(isBeyond90Days(day0, day0 + 90 * dayMs)).toBe(false)
    expect(isBeyond90Days(day0, day0 + 91 * dayMs)).toBe(true)
    expect(isBeyond90Days(day0, day0 - 90 * dayMs)).toBe(false)
    expect(isBeyond90Days(day0, day0 - 91 * dayMs)).toBe(true)
  })
})
