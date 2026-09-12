# 依赖登记簿（锁定文件）

> 规则（章程 3.5 / AGENTS.md §3）：新依赖引入前必须 ① 官方注册表核验真实存在 → ② 流程审批（PO/安全专员）→ ③ 在本登记簿登记 → ④ pom 中经根 `dependencyManagement` 统一锁版。**未登记依赖禁止引入。**

| 坐标 | 锁定版本 | 用途 | 许可证 | 核验来源与日期 | 审批记录 | 引入任务 |
| --- | --- | --- | --- | --- | --- | --- |
| `net.logstash.logback:logstash-logback-encoder` | 8.1 | Spring Boot 内置结构化日志（logstash 格式）的底层 JSON 编码器 | Apache-2.0（含 MIT 子项） | repo1.maven.org 官方 `maven-metadata.xml`，2026-09-06；8.x 系列最新为 8.1，9.0 需 Jackson 3（与 Boot 3.5 管理的 Jackson 2 冲突）故不采用 | PO 会话批准（2026-09-06，低保真确认"1 同意引入"），签署见 `docs/designs/WBS-2.4.4-lofi.md` 确认记录 | WBS 2.4.4 |
| `org.springframework.boot:spring-boot-starter-oauth2-resource-server` | 3.5.16（Spring Boot 3.5.16 BOM 统一管理，pom 不显式锁版；经根 parent 继承） | common-auth 网关侧 JWT 校验（Spring Security Resource Server，"只校验不签发"场景判定见 lofi"场景判定"节；以 optional 依赖引入，避免默认安全策略波及未开启服务） | Apache-2.0 | repo1.maven.org 官方 `maven-metadata.xml`，2026-09-07 实测核验存在，3.5.x 最新为 3.5.16（与项目 Boot 父版本一致） | PO 预授权：按判定原则自主选定（2026-09-07），留痕见 `docs/designs/WBS-2.4.5-lofi.md`"依赖核验与登记"节与确认记录问题 1"认可" | WBS 2.4.5 |
| `io.projectreactor:reactor-core` | 由 Spring Boot 3.5.16 BOM 管理（实测解析 3.7.19），pom 不显式锁版 | common-auth 反应式适配类（Mono/WebFilter）编译所需；以 optional 引入——网关应用经 webflux 自带 reactor，普通服务不受影响（评审①P2-2 补登记） | Apache-2.0 | 本地仓库实测（dependency:get 经 aliyun 镜像解析成功），2026-09-07 | PO 预授权范围内（resource-server 判定的必要编译配套，非独立选型决策）；如 PO 有异议可在验收时打回 | WBS 2.4.5 |
| `org.springframework:spring-webflux` | 由 Spring Boot 3.5.16 BOM 统一管理，pom 不显式锁版；**test scope**（仅 common-auth 反应式链装配测试用，不传递给任何使用方） | 测试类路径补齐反应式 MVC 类型（ServerHttpSecurity.build 的 exceptionHandling 需要） | Apache-2.0 | 本地仓库实测（BOM 解析，与 webflux 生态一致），2026-09-07 | PO 预授权范围内（reactor 同族测试配套，非运行依赖） | WBS 2.4.5 |
| `org.bouncycastle:bcprov-jdk18on` | 1.85.2（根 pom `dependencyManagement` 显式锁版，2026-09-08 实测官方仓库 latest/release 即此版） | common-crypto 国密算法唯一实现来源（SM2/SM3/SM4，全平台唯一加解密入口；组件只做封装、禁止自研算法——章程红线） | Bouncy Castle Licence（X11/MIT 系宽松许可，零预算可用） | repo1.maven.org 官方 `maven-metadata.xml`，2026-09-08 实测核验存在，latest/release=1.85.2 | PO 预授权：ADR-001 国密生态基线内选型，判定留痕见 `docs/designs/WBS-2.4.6-lofi.md`"场景判定"节与确认记录问题 1"认可" | WBS 2.4.6 |
| `org.springframework.boot:spring-boot-starter-aop` | 3.5.16（Spring Boot 3.5.16 BOM 统一管理，pom 不显式锁版；经根 parent 继承） | common-idempotency 注解切面运行时（@Aspect + SpEL 求值；@Aspect 存在才生效，无副作用） | Apache-2.0 | repo1.maven.org 官方 `maven-metadata.xml`，2026-09-09 实测核验存在 3.5.16（与项目 Boot 父版本一致） | PO 预授权：WBS-2.4.7 lofi 确认（问题 4"认可"：注解用法）+ hifi 签署（2026-09-09:20:30，依赖清单） | WBS 2.4.7 |
| `org.redisson:redisson-spring-boot-starter` | 3.52.0（根 pom `dependencyManagement` 显式锁版，2026-09-09 实测官方仓库 3.x 系列 latest 即此版；4.x 面向 Spring Boot 4.1 与项目 3.5 不符，弃用） | common-idempotency 分布式锁（Redisson RLock：看门狗自动续期/可重入/释放校验，锁协议不自研——沿"组件只封装、禁止自研算法"红线逻辑）+ 幂等 Redis 连接（starter 自带 spring-boot-starter-data-redis）；以 optional 引入，Redis 模式由引入方显式加依赖 | Apache-2.0 | repo1.maven.org 官方 `maven-metadata.xml`，2026-09-09 实测核验存在，3.x latest/release=3.52.0（其 pom 声明 spring-boot 3.5.5，与项目 3.5.16 同主次兼容） | PO 预授权：WBS-2.4.7 lofi 确认（问题 2"选 Redisson"）+ hifi 签署（2026-09-09:20:30，依赖清单） | WBS 2.4.7 |
| `com.fasterxml.jackson.core:jackson-databind` | 由 Spring Boot 3.5.16 BOM 管理（pom 不显式锁版） | common-idempotency 幂等返回值序列化/反序列化（结果缓存；组件自建 ObjectMapper，不依赖 web 栈） | Apache-2.0 | 本地仓库实测（dependency:get 经 aliyun 镜像解析成功，Boot 3.5.16 BOM 解析），2026-09-09 | PO 预授权：WBS-2.4.7 hifi 签署（2026-09-09:20:30，依赖清单） | WBS 2.4.7 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 由 Spring Boot 3.5.16 BOM 管理（pom 不显式锁版） | common-idempotency 幂等返回值 java.time 类型（Instant/LocalDateTime 等）序列化支持（ResultCodec 注册 JavaTimeModule） | Apache-2.0 | 本地仓库实测（dependency:get 经 aliyun 镜像解析成功，Boot 3.5.16 BOM 解析），2026-09-09 | PO 预授权：WBS-2.4.7 hifi 签署（2026-09-09:20:30，依赖清单）；本行为编码期补充（返回值 java.time 支持，Spring 生态标准配套） | WBS 2.4.7 |
| `org.springframework.boot:spring-boot-starter-jdbc` | 3.5.16（Spring Boot 3.5.16 BOM 统一管理，pom 不显式锁版；经根 parent 继承） | example-service 迁移演示数据访问（JdbcTemplate）+ Flyway 自动装配前提（DataSource/事务管理）；仅 mysql profile 实际启用，默认 profile 排除 DataSourceAutoConfiguration 保持无库可启动 | Apache-2.0 | Boot BOM 实测（mvn dependency:tree 解析 3.5.16，经 aliyun 镜像），2026-09-12 | PO 预授权：WBS-2.4.10 lofi 问题 5"按判断原则自主选定"（2026-09-12 会话声明），判定留痕见 `docs/designs/WBS-2.4.10-lofi.md`"问题确认"节 | WBS 2.4.10 |
| `org.flywaydb:flyway-core` | 11.7.2（Spring Boot 3.5.16 BOM 管理，pom 不显式锁版；dependency:tree 实测解析） | 数据库迁移引擎（版本化管理/校验和防篡改/flyway_schema_history），ADR-009 规范载体，Spring Boot 官方 auto-config 集成 | Apache-2.0 | 阿里云镜像 mvn dependency:tree 实测 11.7.2（Boot 3.5.16 BOM 解析），2026-09-12 | PO 预授权：WBS-2.4.10 lofi 问题 5"按判断原则自主选定"（2026-09-12 会话声明），判定留痕见 `docs/designs/WBS-2.4.10-lofi.md`"问题确认"节；工具选型判定见同文件"场景判定"节 | WBS 2.4.10 |
| `org.flywaydb:flyway-mysql` | 11.7.2（Spring Boot 3.5.16 BOM 管理，pom 不显式锁版；runtime scope） | Flyway 8.2+ 拆分的 MySQL 方言支持（缺它 MySQL 连接不被迁移引擎识别） | Apache-2.0 | 阿里云镜像 mvn dependency:tree 实测 11.7.2（与 flyway-core 同版本，2026-09-12） | PO 预授权：WBS-2.4.10 lofi 问题 5"按判断原则自主选定"（2026-09-12 会话声明），判定留痕见 `docs/designs/WBS-2.4.10-lofi.md`"问题确认"节 | WBS 2.4.10 |
| `com.mysql:mysql-connector-j` | 9.7.0（Spring Boot 3.5.16 BOM 管理，pom 不显式锁版；runtime scope） | MySQL 8 JDBC 驱动（ADR-001 冻结栈配套） | GPL v2 + FOSS 异常（MySQL Connector/J 官方许可，运行时使用无传染问题） | 阿里云镜像 mvn dependency:tree 实测 9.7.0（2026-09-12） | PO 预授权：WBS-2.4.10 lofi 问题 5"按判断原则自主选定"（2026-09-12 会话声明），判定留痕见 `docs/designs/WBS-2.4.10-lofi.md`"问题确认"节 | WBS 2.4.10 |
| `org.testcontainers:junit-jupiter` | 1.21.4（Spring Boot 3.5.16 BOM 管理（BOM `testcontainers.version`=1.21.4），pom 不显式锁版；**test scope**，不进业务制品） | Testcontainers JUnit 5 扩展（@Testcontainers/@Container/disabledWithoutDocker Docker 探测），ADR-010 规范载体 | Apache-2.0 | Boot BOM 实测（testcontainers.version=1.21.4）+ 本地仓库 1.21.4 构件缓存核验（2026-09-12） | PO 预授权：WBS-2.4.11 lofi 问题 5"按判断原则自主选定"（预授权惯例，判定留痕见 `docs/designs/WBS-2.4.11-lofi.md`"问题确认"节） | WBS 2.4.11 |
| `org.testcontainers:mysql` | 1.21.4（同上 BOM 管理；**test scope**） | MySQL 8 容器模块（MySQLContainer，镜像显式标签 mysql:8.0，ADR-010 禁 latest） | Apache-2.0 | 同上（本地仓库 1.21.4 构件缓存核验，2026-09-12） | 同上 | WBS 2.4.11 |
| `org.springframework.boot:spring-boot-testcontainers` | 3.5.16（Boot BOM 同版本管理；**test scope**） | @ServiceConnection 容器连接参数自动注入（Spring Boot 3.1+ 官方集成） | Apache-2.0 | Boot BOM 实测（与项目 Boot 父版本一致），2026-09-12 | 同上 | WBS 2.4.11 |

## 前端 npm 依赖（WBS 2.4.9，frontend/）

> 前端工程为独立 npm 工程（不进 Maven），锁定文件 = `frontend/package-lock.json`（npm install 自动生成，随分支提交）。版本组合以官方 create-vite vue-ts 模板锁定线 + npmmirror 官方注册表实测核验为准；版本记法为 package.json semver 范围。

| 包名（npm） | 锁定范围 | 用途 | 许可证 | 核验来源与日期 | 审批记录 | 引入任务 |
| --- | --- | --- | --- | --- | --- | --- |
| `vue` | ^3.5.42 | 框架核心（ADR-001 冻结栈） | MIT | create-vite 9.2.0 模板（^3.5.41）+ npmmirror 实测 latest=3.5.42，2026-09-10 | PO 预授权：WBS-2.4.9 lofi 问题 1"认可"，签署见 `docs/designs/WBS-2.4.9-lofi.md` 确认记录 | WBS 2.4.9 |
| `vue-router` | ^5.3.1 | 路由（H3/H4；peer vue ^3.5.34 || ^4.0.0，pinia peer optional 不引入） | MIT | npmmirror 实测 5.3.1 + peerDependencies 核验，2026-09-10 | 同上 | WBS 2.4.9 |
| `element-plus` | ^2.14.5 | UI 组件库（ADR-001 冻结栈） | MIT | npmmirror 实测 2.14.5，2026-09-10 | 同上 | WBS 2.4.9 |
| `@element-plus/icons-vue` | ^2.3.2 | 图标库（peer vue ^3.2.0） | MIT | npmmirror 实测 2.3.2，2026-09-10 | 同上 | WBS 2.4.9 |
| `vite` | ^8.2.2 | 构建/开发服务器（ADR-001 冻结栈） | MIT | create-vite 模板（^8.2.2）+ npmmirror 实测 8.2.2，2026-09-10 | 同上 | WBS 2.4.9 |
| `@vitejs/plugin-vue` | ^6.0.8 | Vue SFC 编译插件（peer vite ^5–^8） | MIT | 模板 + npmmirror 实测 6.0.8，2026-09-10 | 同上 | WBS 2.4.9 |
| `typescript` | ~6.0.2 | TS 语言（模板锁定线；vue-tsc 3.3.11 peer ≥5.0，不追 7.x 最新线） | Apache-2.0 | create-vite 模板锁定 ~6.0.2，2026-09-10 | 同上 | WBS 2.4.9 |
| `vue-tsc` | ^3.3.11 | 类型检查（build 阶段） | MIT | 模板 + npmmirror 实测 3.3.11，2026-09-10 | 同上 | WBS 2.4.9 |
| `@vue/tsconfig` | ^0.9.1 | TS 配置基准（模板自带） | MIT | 模板锁定，2026-09-10 | 同上 | WBS 2.4.9 |
| `@types/node` | ^24.13.3 | Node 类型（模板自带） | MIT | 模板锁定，2026-09-10 | 同上 | WBS 2.4.9 |
| `vitest` | ^5.0.0 | 单元测试框架（peer vite ^6.4–^8） | MIT | npmmirror 实测 5.0.0，2026-09-10 | 同上 | WBS 2.4.9 |
| `@vue/test-utils` | ^2.5.0 | 组件挂载测试（peer vue 3.x） | MIT | npmmirror 实测 2.5.0，2026-09-10 | 同上 | WBS 2.4.9 |
| `jsdom` | ^30.0.1 | Vitest DOM 环境 | MIT | npmmirror 实测 30.0.1，2026-09-10 | 同上 | WBS 2.4.9 |
| `eslint` | ^9.39.5 | 代码检查（dist-tags maintenance 稳定线；eslint-plugin-vue 10.11.0 支持 ^9；**9.39.5 为 9 线最终维护版，npm 提示 EOL 属预期，V1.0 内不升级 10.x**） | MIT | npmmirror 实测 9.39.5，2026-09-10 | 同上 | WBS 2.4.9 |
| `eslint-plugin-vue` | ^10.11.0 | Vue 规则集（peer eslint ^8.57–^10；@stylistic/@typescript-eslint 为 optional） | MIT | npmmirror 实测 10.11.0，2026-09-10 | 同上 | WBS 2.4.9 |
| `vue-eslint-parser` | ^10.4.1 | .vue 文件解析 | MIT | npmmirror 实测 10.4.1，2026-09-10 | 同上 | WBS 2.4.9 |
| `@typescript-eslint/parser` | ^8.70.0 | `<script setup lang="ts">` 解析（vue-eslint-parser 配 parser；ESLint 对 TS 语法必需的前置解析器） | MIT | npmmirror 实测 8.70.0，2026-09-10 | PO 预授权范围内（eslint 生态必要配套，编码期补充；lofi 问题 1 授权"按冻结栈内自主选定"覆盖） | WBS 2.4.9 |
