/**
 * WBS-3.10.4 AI 配置域常量（hifi §5 / lofi W3；C-11.1A B2/B3）：
 * - 判定三态 LABELS/TAG（E3 decision；未知枚举值原样显示）；
 * - 供应商封闭清单守卫（glm/deepseek/qwen/kimi，ADR-003 §2.1；显示名以后端视图 providerName 承载）；
 * - 密钥列固定灰字与诚实边界口径原文（Q4-A / 规格 B4-3，页面与交付说明一致）；
 * - 前置校验纯函数 validateAiConfigForm（视图保存前调用；后端仍权威复核）。
 */

/** 判定三态业务文案（与后端 AiConfigService reason 常量一致口径）。 */
export const DECISION_LABELS: Record<string, string> = {
  ALLOW: '允许（当前配置下网关放行）',
  DENIED_DISABLED: '拒绝：网关未启用',
  DENIED_BUDGET_EXCEEDED: '拒绝：超出预算',
}

/** 判定三态徽标色（ALLOW 绿 / 其余红）。 */
export const DECISION_TAG_TYPES: Record<string, string> = {
  ALLOW: 'success',
  DENIED_DISABLED: 'danger',
  DENIED_BUDGET_EXCEEDED: 'danger',
}

/** 未知枚举值原样显示（沿 constants/platform labelOf 先例）。 */
export function decisionLabelOf(value: string): string {
  return DECISION_LABELS[value] ?? value
}

export function decisionTagTypeOf(value: string): string {
  return DECISION_TAG_TYPES[value] ?? 'info'
}

/** 供应商封闭清单（保存载荷与默认供应商下拉共用；顺序 = 视图顺序，glm 默认居首）。 */
export const AI_PROVIDER_IDS = ['glm', 'deepseek', 'qwen', 'kimi'] as const

/** 密钥列固定灰字（页面零密钥输入/回显，Q4-A）。 */
export const AI_KEY_NOTE = '部署期经配置中心/KMS 注入（页面不显示）'

/** 页面顶部常驻诚实边界条原文（规格 B4-3）。 */
export const AI_CONFIG_HONESTY_NOTICE =
  '诚实边界：V1.0 配置即预留——配置落库可读写可演示，但平台尚无任何真实 AI 调用消费它（零外呼零消耗）；' +
  '生效对象 = 未来 V1.5 / C-11.x 绑定。模型密钥只进配置中心/KMS，页面不显示。'

/** 判定预览口径灰字（规格 B3-2：已用量恒 0，超预算分支不可触发）。 */
export const AI_PREVIEW_NOTE =
  '预览按已用量恒为 0 执行（V1.0 无用量累计源），"超预算"分支不可触发（判定语义由 3.10.1 单测承载）。' +
  '预览为纯判定，零网络调用、零计费。'

/** 前置校验失败消息（AiConfigSaveRequest 形态；后端 1014C0001 权威复核）。 */
export function validateAiConfigForm(form: {
  defaultProvider: string
  tokenBudget: number
  providers: { providerId: string; baseUrl: string | null; model: string | null }[]
}): string | null {
  if (!Number.isInteger(form.tokenBudget) || form.tokenBudget < 0) {
    return 'token 预算须为 ≥0 的整数'
  }
  if (!AI_PROVIDER_IDS.includes(form.defaultProvider as (typeof AI_PROVIDER_IDS)[number])) {
    return '默认供应商不在封闭清单内（glm/deepseek/qwen/kimi）'
  }
  const ids = form.providers.map((item) => item.providerId)
  if (
    ids.length !== AI_PROVIDER_IDS.length ||
    new Set(ids).size !== AI_PROVIDER_IDS.length ||
    ids.some((id) => !AI_PROVIDER_IDS.includes(id as (typeof AI_PROVIDER_IDS)[number]))
  ) {
    return '供应商编号不在封闭清单内（glm/deepseek/qwen/kimi）'
  }
  for (const item of form.providers) {
    if (item.baseUrl && item.baseUrl.trim()) {
      if (!/^https?:\/\/\S+$/i.test(item.baseUrl.trim()) || item.baseUrl.trim().length > 512) {
        return '服务地址须为 http(s) URL，或留空表示未配置'
      }
    }
    if (item.model && item.model.trim().length > 128) {
      return '默认模型名不得超过 128 字符'
    }
  }
  return null
}
