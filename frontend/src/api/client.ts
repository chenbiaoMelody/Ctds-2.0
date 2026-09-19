/**
 * WBS-3.1.5 演示期 API 封装（真实登录/令牌待 3.9.1，前端拦截仅为交互体验，
 * 演示期服务直连信任客户端身份头，安全边界 = 网络隔离（127.0.0.1）+ 3.5.2 网关 + 3.9.1 真实令牌，
 * 详见 ADR-016 §2.7）：
 * - 统一附加演示身份头（X-Ctds-Subject / X-Ctds-Roles，直连无网关过渡口径）；
 * - admin 演示角色兼任审核员（WBS-3.1.5 lofi 问题 1 采 A：roles = applicant,reviewer）；
 * - 统一解析 ADR-005 ApiResult（code="0" 成功，其余抛 ApiError 保留九位错误码与业务文案）。
 */
import { getDemoRole } from '../stores/demoRole'

const SUBJECT_KEY = 'ctds-demo-subject'
const DEFAULT_SUBJECT = 'demo-applicant'

/** 演示身份（注册建档 applicant 依据；同一浏览器会话内保持一致）。 */
export function getDemoSubject(): string {
  return localStorage.getItem(SUBJECT_KEY) || DEFAULT_SUBJECT
}

export function setDemoSubject(value: string): void {
  localStorage.setItem(SUBJECT_KEY, value)
}

/** 演示角色 → 后端角色头（reviewer 角色映射 subject.read/subject.review）。 */
export function demoRolesHeader(): string {
  return getDemoRole() === 'admin' ? 'applicant,reviewer' : 'applicant'
}

/** 后端业务错误（code = 九位错误码，message = 业务可读文案）。 */
export class ApiError extends Error {
  readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.code = code
  }
}

interface ApiResultEnvelope<T> {
  code: string
  message: string
  data: T
}

/** JSON 请求封装：附身份头 → 断言 code="0" → 返回 data。 */
export async function apiJson<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'X-Ctds-Subject': getDemoSubject(),
      'X-Ctds-Roles': demoRolesHeader(),
      ...(options.headers || {}),
    },
  })
  const body = (await response.json()) as ApiResultEnvelope<T>
  if (body.code !== '0') {
    throw new ApiError(body.code, body.message)
  }
  return body.data
}

/** multipart 上传封装（不设 Content-Type，由浏览器带 boundary）。 */
export async function apiUpload<T>(path: string, file: File): Promise<T> {
  const form = new FormData()
  form.append('file', file)
  const response = await fetch(path, {
    method: 'POST',
    body: form,
    headers: {
      'X-Ctds-Subject': getDemoSubject(),
      'X-Ctds-Roles': demoRolesHeader(),
    },
  })
  const body = (await response.json()) as ApiResultEnvelope<T>
  if (body.code !== '0') {
    throw new ApiError(body.code, body.message)
  }
  return body.data
}
