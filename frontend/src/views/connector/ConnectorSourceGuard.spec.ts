import { describe, it, expect } from 'vitest'

/**
 * 连接器界面源集守卫（WBS-3.6.7 hifi §8.1 T13，沿 3.5.6 DeliverySourceGuard 先例）：
 * ① 视图与 API 源集零 `/internal/`（内部面零接触，N8）；
 * ② 零 `X-Idempotency-Key`（连接器域未启用幂等，界面不做假幂等）；
 * ③ 零 `connector-internal` 角色字面量与内部权限点；
 * ④ 零服务端错误文案 / 错误码副本（一律展示 ApiError.message）；
 * ⑤ 零凭据明文渲染（列表只读 credentialMasked；凭据仅表单输入且提交后清空）；
 * ⑥ 零数据本体字段；视图不散写 admin 角色头。
 */
const viewSources = import.meta.glob('./*.vue', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const apiSources = import.meta.glob('../../api/connector.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const constantSources = import.meta.glob('../../constants/connector.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const viewFiles = Object.keys(viewSources)
const apiText = Object.values(apiSources).join('')
const constantText = Object.values(constantSources).join('')

const SERVER_CODE_LITERALS = [
  '1010C0001',
  '1010C0002',
  '1010C0003',
  '1010C0004',
  '1010C0006',
  '1010C0008',
  '1010C0009',
  '1010C0010',
  '1010S0001',
  '1010S0002',
  '1010S0004',
  '1000C0002',
]

describe('连接器界面源集守卫（T13）', () => {
  it('连接器视图源集存在且为 3 个（管理 / 详情 / 治理）', () => {
    expect(viewFiles.sort()).toEqual([
      './ConnectorDetailView.vue',
      './ConnectorGovernanceView.vue',
      './ConnectorManageView.vue',
    ])
  })

  it('零内部面调用：视图 / API / 常量均不含 /internal/', () => {
    for (const [file, source] of Object.entries({ ...viewSources, '../../api/connector.ts': apiText, '../../constants/connector.ts': constantText })) {
      expect(source, `${file} 触碰内部面`).not.toContain('/internal/')
    }
  })

  it('无幂等键：零 X-Idempotency-Key（连接器端点未启用幂等）', () => {
    for (const source of [apiText, ...Object.values(viewSources)]) {
      expect(source).not.toContain('X-Idempotency-Key')
      expect(source).not.toContain('idempotencyKey')
    }
  })

  it('零 connector-internal 角色与内部权限点（任何档位不得携带）', () => {
    for (const source of [apiText, constantText, ...Object.values(viewSources)]) {
      expect(source).not.toContain('connector-internal')
      expect(source).not.toContain('connector.internal.read')
      expect(source).not.toContain('connector.internal.write')
    }
  })

  it('零服务端错误码 / 文案副本：视图与 API 不出现九位码字面量', () => {
    for (const [file, source] of Object.entries({ ...viewSources, '../../api/connector.ts': apiText })) {
      for (const code of SERVER_CODE_LITERALS) {
        expect(source, `${file} 内置了服务端错误码 ${code}`).not.toContain(code)
      }
    }
  })

  it('零凭据明文渲染：列表只读 credentialMasked，视图不直读记录行的 credential 字段', () => {
    // 凭据只允许出现在登记 / 变更弹窗的表单模型（sourceForm.credential），
    // 记录行（row / item / record / source）一律只读掩码字段。
    for (const [file, source] of Object.entries(viewSources)) {
      expect(source, `${file} 直读了凭据字段`).not.toMatch(/\b(row|item|record|source)\.credential\b/)
    }
    expect(Object.values(viewSources).join('')).toContain('credentialMasked')
  })

  it('零数据本体：视图源集无数据正文 / 原文类字段与文案', () => {
    for (const [file, source] of Object.entries(viewSources)) {
      expect(source, `${file} 含本体类词`).not.toMatch(/正文|原文|数据内容|文件内容/)
    }
  })

  it("视图不散写 'admin' 角色头字面量（角色头收口在 demoIdentity.ts）", () => {
    for (const [file, source] of Object.entries(viewSources)) {
      expect(source, `${file} 出现 admin 字面量`).not.toContain("'admin'")
      expect(source, `${file} 出现 admin 字面量`).not.toContain('"admin"')
    }
    expect(apiText).not.toContain("'admin'")
  })
})
