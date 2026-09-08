# 依赖登记簿（锁定文件）

> 规则（章程 3.5 / AGENTS.md §3）：新依赖引入前必须 ① 官方注册表核验真实存在 → ② 流程审批（PO/安全专员）→ ③ 在本登记簿登记 → ④ pom 中经根 `dependencyManagement` 统一锁版。**未登记依赖禁止引入。**

| 坐标 | 锁定版本 | 用途 | 许可证 | 核验来源与日期 | 审批记录 | 引入任务 |
| --- | --- | --- | --- | --- | --- | --- |
| `net.logstash.logback:logstash-logback-encoder` | 8.1 | Spring Boot 内置结构化日志（logstash 格式）的底层 JSON 编码器 | Apache-2.0（含 MIT 子项） | repo1.maven.org 官方 `maven-metadata.xml`，2026-09-06；8.x 系列最新为 8.1，9.0 需 Jackson 3（与 Boot 3.5 管理的 Jackson 2 冲突）故不采用 | PO 会话批准（2026-09-06，低保真确认"1 同意引入"），签署见 `docs/designs/WBS-2.4.4-lofi.md` 确认记录 | WBS 2.4.4 |
| `org.springframework.boot:spring-boot-starter-oauth2-resource-server` | 3.5.16（Spring Boot 3.5.16 BOM 统一管理，pom 不显式锁版；经根 parent 继承） | common-auth 网关侧 JWT 校验（Spring Security Resource Server，"只校验不签发"场景判定见 lofi"场景判定"节；以 optional 依赖引入，避免默认安全策略波及未开启服务） | Apache-2.0 | repo1.maven.org 官方 `maven-metadata.xml`，2026-09-07 实测核验存在，3.5.x 最新为 3.5.16（与项目 Boot 父版本一致） | PO 预授权：按判定原则自主选定（2026-09-07），留痕见 `docs/designs/WBS-2.4.5-lofi.md`"依赖核验与登记"节与确认记录问题 1"认可" | WBS 2.4.5 |
| `io.projectreactor:reactor-core` | 由 Spring Boot 3.5.16 BOM 管理（实测解析 3.7.19），pom 不显式锁版 | common-auth 反应式适配类（Mono/WebFilter）编译所需；以 optional 引入——网关应用经 webflux 自带 reactor，普通服务不受影响（评审①P2-2 补登记） | Apache-2.0 | 本地仓库实测（dependency:get 经 aliyun 镜像解析成功），2026-09-07 | PO 预授权范围内（resource-server 判定的必要编译配套，非独立选型决策）；如 PO 有异议可在验收时打回 | WBS 2.4.5 |
| `org.springframework:spring-webflux` | 由 Spring Boot 3.5.16 BOM 统一管理，pom 不显式锁版；**test scope**（仅 common-auth 反应式链装配测试用，不传递给任何使用方） | 测试类路径补齐反应式 MVC 类型（ServerHttpSecurity.build 的 exceptionHandling 需要） | Apache-2.0 | 本地仓库实测（BOM 解析，与 webflux 生态一致），2026-09-07 | PO 预授权范围内（reactor 同族测试配套，非运行依赖） | WBS 2.4.5 |
