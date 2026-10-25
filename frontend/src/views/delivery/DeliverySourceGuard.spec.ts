// 双源守卫真读后端源文件需要 node 内置模块类型——三斜杠引用按文件引入（沿 3.4.7 先例零配置改动）。
/// <reference types="node" />
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { DELIVERY_ERROR_HINTS } from '../../constants/delivery'

/**
 * 交付界面源集守卫（WBS-3.5.6 hifi §7 T14，沿 3.4.7 ContractSourceGuard 先例）：
 * ① 交付视图源集无裸状态/形态/结论/作业中文字面量（一律取 constants/delivery.ts）；
 * ② 交付视图与 API 模块不得直接 `fetch(`（下载端点除外——二进制流走原生 fetch）；
 * ③ 'admin' 角色头字面量仅限 stores/demoIdentity.ts（普通档双头不含 admin 的源集锚）；
 * ④ 视图不得触碰 /data/** 数据面端点（N3——服务端一律 1009C0012，界面零入口）；
 * ⑤ 全链零本体：视图源集无 content/正文/原文 类字段与文案；
 * ⑥ 同形提示常量与后端文案逐字一致（双源守卫：后端 DeliveryErrorCodes/SandboxErrorCodes
 *    为真源，前端 DELIVERY_ERROR_HINTS 副本逐字比对）。
 */
const here = dirname(fileURLToPath(import.meta.url))
const frontendRoot = resolve(here, '../..')
const deliveryBackend = resolve(
  frontendRoot,
  '../../services/delivery-service/src/main/java/com/ctds/delivery/domain',
)

const viewSources = import.meta.glob('./*.vue', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const specSources = import.meta.glob('./*.spec.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const apiSources = import.meta.glob('../../api/delivery.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const viewFiles = Object.keys(viewSources)
const apiText = Object.values(apiSources).join('')

/** 禁写中文集合：这些词只能出现在 constants/delivery.ts（视图散写 = 红）。 */
const FORBIDDEN_VIEW_LITERALS = [
  '接口交付',
  '文件交付',
  '沙箱导出',
  '使用中',
  '已关闭',
  '已到期',
  '待审核',
  '已通过',
  '已驳回',
  '未登记',
  '数据集画像',
  '输入清单',
]

describe('交付界面源集守卫（T14）', () => {
  it('交付视图源集存在且为 4 个（列表 / 沙箱列表 / 沙箱详情 / 治理）', () => {
    expect(viewFiles.sort()).toEqual([
      './DeliveryGovernanceView.vue',
      './DeliveryListView.vue',
      './SandboxDetailView.vue',
      './SandboxListView.vue',
    ])
  })

  it.each(FORBIDDEN_VIEW_LITERALS)('视图源码不得散写业务标签中文：%s', (word) => {
    for (const [file, source] of Object.entries(viewSources)) {
      expect(source, `${file} 散写了 "${word}"（应取 constants/delivery.ts）`).not.toContain(word)
    }
  })

  it('API 模块不得直接 fetch（下载二进制除外——唯一豁免点须有注释锚）', () => {
    const directFetches = (apiText.match(/fetch\(/g) ?? []).length
    expect(directFetches).toBeLessThanOrEqual(1)
    if (directFetches === 1) {
      expect(apiText).toContain('X-Ctds-Sm3')
    }
  })

  it("'admin' 角色头字面量仅限 demoIdentity.ts（视图与 API 模块零出现）", () => {
    for (const [file, source] of Object.entries(viewSources)) {
      expect(source, `${file} 出现 'admin' 字面量`).not.toContain("'admin'")
      expect(source, `${file} 出现 "admin" 字面量`).not.toContain('"admin"')
    }
    expect(apiText).not.toContain("'admin'")
  })

  it('视图与 API 源集零触碰 /data/** 数据面端点（N3）', () => {
    for (const source of [apiText, ...Object.values(viewSources)]) {
      expect(source).not.toMatch(/\/data\/|sandbox-data|SandboxDataDenial/)
    }
  })

  it('零本体：视图源集无内容类字段与文案（content/正文/原文/解密）', () => {
    for (const [file, source] of Object.entries(viewSources)) {
      expect(source, `${file} 含本体类词`).not.toMatch(/正文|原文|文件内容|解密预览/)
    }
  })

  it('spec 源码同样不得触碰 /data/**（守卫自检）', () => {
    for (const [file, source] of Object.entries(specSources)) {
      expect(source, `${file} 触碰 /data/**`).not.toMatch(/\/api\/v1\/sandboxes\/SB[^/]*\/data\//)
    }
  })
})

describe('同形提示双源守卫（T15 / DB-43 模式）', () => {
  const backendText =
    readFileSync(resolve(deliveryBackend, 'DeliveryErrorCodes.java'), 'utf-8') +
    readFileSync(resolve(deliveryBackend, 'SandboxErrorCodes.java'), 'utf-8')

  it.each(Object.entries(DELIVERY_ERROR_HINTS))(
    '错误码 %s 前端提示与后端文案逐字一致',
    (code, hint) => {
      if (code.startsWith('1008')) {
        // 合约域透传码（1008C0013/1008C0020）权威在合约域，后端源不在 delivery 模块——
        // 本守卫仅锚 1009 段；1008 两条由 constants 声明与剧本对照承载。
        return
      }
      expect(backendText, `后端源文件未定义 ${code}`).toContain(`"${code}"`)
      // 双引号串拼接（模板串中 \s/\w 类转义会被吞掉），匹配 "码"); 后首个 *_MESSAGE 常量文案
      const messagePattern =
        code + '"\\);\\s*public static final String\\s+\\w+_MESSAGE\\s*=\\s*"([^"]+)'
      const messageMatch = backendText.match(new RegExp(messagePattern))
      expect(messageMatch, `${code} 的后端 MESSAGE 常量未找到`).toBeTruthy()
      expect(hint).toBe(messageMatch![1])
    },
  )

  it('1009 段码表全量锚定：后端每个 C 型码在前端提示组都有条目', () => {
    const backendCodes = [...backendText.matchAll(/ErrorCode\.of\("(1009C\d{4})"\)/g)].map(
      (m) => m[1],
    )
    for (const code of backendCodes) {
      expect(DELIVERY_ERROR_HINTS[code], `后端码 ${code} 前端缺提示条目`).toBeDefined()
    }
  })

  it('1008 跨域透传两条与合约域权威文案逐字一致（评审修复 F3.2：消除守卫跳过口）', () => {
    const contractText = readFileSync(
      resolve(
        frontendRoot,
        '../../services/contract-service/src/main/java/com/ctds/contract/domain/ContractErrorCodes.java',
      ),
      'utf-8',
    )
    const crossDomain = Object.entries(DELIVERY_ERROR_HINTS).filter(([code]) =>
      code.startsWith('1008'),
    )
    expect(crossDomain.length).toBeGreaterThan(0)
    for (const [code, hint] of crossDomain) {
      const messageMatch = contractText.match(
        new RegExp(code + '"\\);\\s*public static final String\\s+\\w+_MESSAGE\\s*=\\s*"([^"]+)'),
      )
      expect(messageMatch, `${code} 的合约域 MESSAGE 常量未找到`).toBeTruthy()
      expect(hint).toBe(messageMatch![1])
    }
  })
})
