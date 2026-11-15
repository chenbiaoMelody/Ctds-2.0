# WBS-3.9.6 B7-AC3 演算留档（规格 :156"触发或演算二者之一"采演算腿）

| 项 | 内容 |
| --- | --- |
| 关联 | 规格 `docs/specs/C-10.1-10.2-系统管理.md` V1.2 :156（B7-AC3）；剧本 C-10.2 S2-5；hifi §1 T4；任务卡关闭条件④；裁决 = Q3-A（演算腿自动化，编排师 2026-11-15 一次确认） |
| 演算时点 | 2026-11-15 18:31:32（窗口 73s ≥ 60s）；脚本绿相完整运行 exit 0 |
| 脚本 | `build-output/w396-演算/w396-algo.ps1`（PowerShell ASCII-only 单一工具链；gitignored） |
| 原始留档 | `build-output/w396-演算/w396-algo-result.md`（脚本自动生成，逐服务两次取值 + 演算 + 判定）+ `w396-algo-green.log`（运行台账）；红相佐证 = `red-t4-noservice.log`（delivery 停服采样失败 → exit 1 + 零留档，§5 边界 4 验证） |

## 算式（规格原文 + 手工等价映射）

- **规格 :156 PromQL 示例原文**：`sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))`
- **手工等价式**：逐服务 `Δ5xx 计数 / Δ总计数`（两次采样相距 ≥60s）——rate 的窗口均值之比在两采样共享窗口端点时等于计数增量之比；阈值为 5%（`CtdsBackendHighErrorRate` 规则口径，ADR-014 §3.3 封闭两规则之一）。
- **数据源**：演示链 11 服务 `/actuator/prometheus` 真实暴露面（B5-AC3 已 CI 化口径；监控栈未部署不阻碍演算——Q3-A）。

## 逐服务演算结果（摘要，完整取值见原始留档）

| 服务 | s1 总计数 | s2 总计数 | Δ5xx | Δ总 | 5xx 占比 | 判定 |
| --- | --- | --- | --- | --- | --- | --- |
| subject-service / kms / did / space / catalog / contract / gateway / delivery / connector / metering-billing / evidence | 1~4 | 3~6 | 0 | 2 | **0%** | 低于 5% → 规则未触发；算式可判定 |
| **平台聚合** | — | — | **0** | **22** | **0%** | 全服务采样成功 + 演算完成 + 留档写入 → exit 0 |

## 结论与诚实口径（三件套）

1. **算式演算证明规则可判定**：`CtdsBackendHighErrorRate` 的判定算式在真实暴露指标上可计算、可对照阈值（0% < 5% → 当前未触发）——B7-AC3"未触发且无演算记录 = 挂账不判定"的挂账风险已解除，本 AC 采演算腿记为可判定。
2. **演算非真机触发（如实标注）**：窗口流量 = 每服务一次 `/actuator/health` 只读探针（脚本内注明）+ 采样请求本身；未构造 5xx 真流量，未部署监控栈（WBS 2.5.3 未立卡，编排师层面呈报项）。
3. **不掩盖不伪造**：若演算占比 ≥5%，脚本按 §5 边界 5 如实记录"达到触发条件，规则未接真流量"并照常 exit 0（演算完成 ≠ 触发判定）；本次实测 0%，未达阈值。
