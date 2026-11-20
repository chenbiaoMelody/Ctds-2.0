/**
 * WBS-3.10.4 AI 配置域 API 封装（hifi §3/§4；沿 api/platform.ts 先例）：
 * - URL 单点：4 端点 URL 仅本文件持有（SourceGuard 口径：视图零 URL）；
 * - 独立命名空间 /api/v1/ai-config（不占用部署域读面封闭面；后端 evidence-service，端口 8090）；
 * - 域工厂复用 `makeDomainJson(platformRolesHeader)`（platform 域头随三员视角，sysadmin 缺省）；
 * - 保存 = POST /save 全量载荷（hifi §1.2；写面沿仓内写面 POST 惯例）；
 * - 留痕分页复用共享 `pageQuery`（DB-105：不建视图私有副本）；
 * - 出参零密钥字段（api_key_ref 不进读面响应，红线由后端 AiConfigViews 契约承载）。
 */
import { makeDomainJson, pageQuery } from './client'
import type { PageData } from './types'
import { platformRolesHeader } from '../stores/demoIdentity'

// ==== URL 单点（SourceGuard：全仓仅此处持有这 4 条 URL） ====

export const AI_CONFIG_URL = '/api/v1/ai-config'
export const AI_CONFIG_SAVE_URL = '/api/v1/ai-config/save'
export const AI_CONFIG_PREVIEW_URL = '/api/v1/ai-config/preview'
export const AI_CONFIG_CHANGES_URL = '/api/v1/ai-config/changes'

// ==== 共享类型（与后端 AiConfigViews 出参逐字段一致；零密钥字段） ====

/** 供应商行视图（configured = 地址或模型名至少一项非空）。 */
export interface AiConfigProviderView {
  providerId: string
  providerName: string
  isDefaultProvider: boolean
  baseUrl: string | null
  model: string | null
  configured: boolean
}

/** 合并视图（GET /ai-config；缺省态由后端 V6 seed 承载）。 */
export interface AiConfigView {
  enabled: boolean
  defaultProvider: string
  tokenBudget: number
  providers: AiConfigProviderView[]
}

/** 全量保存载荷（POST /save；密钥相关字段零承载）。 */
export interface AiConfigSaveRequest {
  enabled: boolean
  defaultProvider: string
  tokenBudget: number
  providers: { providerId: string; baseUrl: string | null; model: string | null }[]
}

/** 判定预览（GET /preview；decision 三态 + 业务可读 reason）。 */
export interface AiConfigPreviewView {
  decision: 'ALLOW' | 'DENIED_DISABLED' | 'DENIED_BUDGET_EXCEEDED' | string
  reason: string
}

/** 变更留痕行（五要素；before/after 为 null 表示该侧原值为空）。 */
export interface AiConfigChangeLogView {
  changedAt: string
  changedBy: string
  fieldPath: string
  beforeValue: string | null
  afterValue: string | null
}

// ==== 域工厂（platform 域头随三员视角，sysadmin 缺省） ====

const aiJson = makeDomainJson(platformRolesHeader)

/** 读当前配置合并视图（挂 platform.ai.config.read）。 */
export function fetchAiConfig(): Promise<AiConfigView> {
  return aiJson<AiConfigView>(AI_CONFIG_URL)
}

/** 全量保存（挂 platform.ai.config.write；唯一写入口；校验拒绝 1014C0001 原文透传）。 */
export function saveAiConfig(req: AiConfigSaveRequest): Promise<AiConfigView> {
  return aiJson<AiConfigView>(AI_CONFIG_SAVE_URL, { method: 'POST', body: JSON.stringify(req) })
}

/** 当前判定预览（挂 platform.ai.config.read；纯判定零外呼）。 */
export function fetchAiConfigPreview(): Promise<AiConfigPreviewView> {
  return aiJson<AiConfigPreviewView>(AI_CONFIG_PREVIEW_URL)
}

/** 变更留痕分页（挂 platform.ai.config.read；五列只读）。 */
export function fetchAiConfigChanges(pageNum: number, pageSize: number): Promise<PageData<AiConfigChangeLogView>> {
  return aiJson<PageData<AiConfigChangeLogView>>(`${AI_CONFIG_CHANGES_URL}${pageQuery(pageNum, pageSize)}`)
}
