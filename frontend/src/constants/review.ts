/**
 * 评价域常量单一源（WBS-3.7.4 hifi §5.1；C-7.1 规格业务文案）。
 * 拒绝文案与后端 1011 段同源直译，不在视图层二次包装；评分标签 / 隐藏确认文案收口于此。
 */

/** 评价区块标题（hifi §5.2 区块名）。 */
export const REVIEW_SECTION_TITLE = '产品评价'

/** 空态文案（统计无评价 + 零条目共用）。 */
export const REVIEW_EMPTY_TIP = '暂无评价'

/** 无交付资格拒绝文案（与后端 1011C0006 逐字一致——S2-10 负向演示路径）。 */
export const REVIEW_ELIGIBILITY_DENIED_TIP = '完成对该产品的有效交付后方可评价'

/** 非法评分拦截文案（与后端 1011C0005 逐字一致——S2-9 前端拦截 + 服务端兜底双保险）。 */
export const REVIEW_SCORE_INVALID_TIP = '评分需为 1~5 的整数'

/** 隐藏确认弹窗固定文案（N3 最小口径：无原因输入）。 */
export const REVIEW_HIDE_CONFIRM_TEXT = '确认隐藏该评价？隐藏后不再对外展示，操作将留痕。'

/** 提交 / 更新按钮文案（本人已评价时表单转更新态）。 */
export const REVIEW_SUBMIT_LABEL = '提交评价'
export const REVIEW_UPDATE_LABEL = '更新评价'

/** 表单文字评价占位（hifi §5.2）。 */
export const REVIEW_COMMENT_PLACEHOLDER = '说说你的使用体验（可选）'

/** 评分字段标签。 */
export const REVIEW_SCORE_LABEL = '评分'

/** 错误码同名词表（与后端 ReviewErrorCodes 对应；透传断言进 spec）。 */
export const REVIEW_ERROR_CODES = {
  SCORE_INVALID: '1011C0005',
  ELIGIBILITY_DENIED: '1011C0006',
  REVIEW_NOT_FOUND: '1011C0007',
} as const

/** 列表条目元信息："评价人 {requesterNo} · {yyyy-MM-dd HH:mm}"（createdAt 为后端 ISO 口径）。 */
export function formatReviewMeta(requesterNo: string, createdAt: string): string {
  const stamp = createdAt.replace('T', ' ').slice(0, 16)
  return `评价人 ${requesterNo} · ${stamp}`
}

/** 统计条文案："平均评分 X.X · 共 N 条评价"（averageScore null 时不适用——调用方走空态）。 */
export function formatReviewStats(averageScore: number, reviewCount: number): string {
  return `平均评分 ${averageScore.toFixed(1)} · 共 ${reviewCount} 条评价`
}
