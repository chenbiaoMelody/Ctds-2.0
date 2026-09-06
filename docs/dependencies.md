# 依赖登记簿（锁定文件）

> 规则（章程 3.5 / AGENTS.md §3）：新依赖引入前必须 ① 官方注册表核验真实存在 → ② 流程审批（PO/安全专员）→ ③ 在本登记簿登记 → ④ pom 中经根 `dependencyManagement` 统一锁版。**未登记依赖禁止引入。**

| 坐标 | 锁定版本 | 用途 | 许可证 | 核验来源与日期 | 审批记录 | 引入任务 |
| --- | --- | --- | --- | --- | --- | --- |
| `net.logstash.logback:logstash-logback-encoder` | 8.1 | Spring Boot 内置结构化日志（logstash 格式）的底层 JSON 编码器 | Apache-2.0（含 MIT 子项） | repo1.maven.org 官方 `maven-metadata.xml`，2026-09-06；8.x 系列最新为 8.1，9.0 需 Jackson 3（与 Boot 3.5 管理的 Jackson 2 冲突）故不采用 | PO 会话批准（2026-09-06，低保真确认"1 同意引入"），签署见 `docs/designs/WBS-2.4.4-lofi.md` 确认记录 | WBS 2.4.4 |
