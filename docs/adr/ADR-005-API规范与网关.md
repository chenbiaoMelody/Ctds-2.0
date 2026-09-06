# ADR-005 API 规范与网关（REST 规范、错误码体系、限流鉴权基线）

| 字段 | 内容 |
| --- | --- |
| 编号 | ADR-005 |
| 状态 | **已批准（2026-09-06，PM+PO 批准方案 A——见 §6 批准签发）** |
| 日期 | 2026-09-06 |
| 关联工作包 | WBS 2.1.5 → 2.4.1/2.4.2/2.4.5（骨架、common-错误码、common-鉴权）；信通院接口约定收敛 std-adapter |

## 1. 背景

10+ 微服务对外/对内接口需要统一规范，否则 AI 并行开发将产生 N 套接口风格（章程 4.3 横切能力收敛原则）。错误码、鉴权、分页等必须先定契约，公共组件（common）才有实现依据。

## 2. 备选方案与业务影响对比

| 维度 | 方案 A：Spring Cloud Gateway（推荐） | 方案 B：APISIX | 方案 C：Nginx + 手写过滤器 |
| --- | --- | --- | --- |
| 与 ADR-001 栈契合 | 同生态，配置即 Java/Spring 风格，AI 生成最稳 | 独立技术栈（Lua/etcd），团队需多学一套 | 看似简单实则逻辑分散难维护 |
| 限流/鉴权插件 | 内置 RequestRateLimiter + GlobalFilter | 插件市场丰富 | 全手写 |
| 运维 | 随应用部署 | 独立进程 | 独立进程 |
| 社区语料 | 最多 | 多（国产开源） | 多但模式老旧 |

## 3. 决策（推荐）

1. **网关**：Spring Cloud Gateway，全平台唯一流量入口；
2. **API 规范（REST + JSON）**：
   - 资源命名：复数名词小写连字符（`/api/v1/data-spaces/{id}/datasets`）；版本在路径（`/api/v1`）；
   - 动作：标准 HTTP 方法语义；非 CRUD 动作用动词子资源（`POST /…/actions/publish`）；
   - 分页：`pageNum`/`pageSize`/`orderBy`，响应含 `total`（由 common-分页组件统一实现，2.4.3）；**数值边界（补充固化 2026-09-06，随 2.4.3 落地）**：pageNum ∈ [1,10000]、pageSize ∈ [1,100]（默认 10）、orderBy 单字段 ≤64 字符（"字段名[,asc|desc]"，持久层须列名白名单映射）；响应字段 = list + total + pageNum/pageSize/totalPages（后三者为派生便利字段）；
   - 时间：ISO-8601 字符串（UTC 存储、东八区展示）；
   - 统一响应结构：`{ "code": "0", "message": "…", "traceId": "…", "data": … }`——**code 为字符串**（"0"=成功，其余为 §3.3 九位错误码；补充澄清 2026-09-06，随 2.4.2 组件落地固化）；对外错误信息不暴露内部实现（章程 4.3）；
   - 幂等：写操作支持 `X-Idempotency-Key` 头（由 common-幂等组件实现，2.4.7）。
3. **错误码体系**：`平台域(2位) + 模块(2位) + 类型(1位字母 C/B/S) + 4位序号`，字符串形式如 `1001C0001`；0/`0000…` 表示成功；具体编码表随 common-错误码组件（2.4.2）建立并全平台唯一；
4. **鉴权基线**：JWT（SM2 签名可选增强）+ RBAC，网关统一校验、服务内部只认网关注入的上下文头（common-鉴权，2.4.5）；
5. **限流基线**：网关层按"主体 + 接口"令牌桶限流，阈值配置化；计量类接口另设配额校验。
6. **日志与审计契约（补充固化 2026-09-06，随 2.4.4 组件落地）**：结构化日志采用 Spring Boot 内置 logstash 格式（`logging.structured.format.console/file: logstash`，底层依赖 logstash-logback-encoder 8.1，经依赖审批并登记于 `docs/dependencies.md`）；JSON 行含 `@timestamp/level/thread/logger/message` 与 MDC 字段（`traceId`/`errorCode`/`module`，有值才出现），traceId 键沿用 `ApiResult.TRACE_MDC_KEY` 单一来源；审计事件接口 `AuditRecorder.record(event)` 铁律"绝不抛错、绝不阻塞"，事件落 `logs/audit-YYYY-MM-DD.jsonl` 按天滚动（字段：eventId/eventTime/service/actor/action/targetType/targetId/outcome/detail/traceId；outcome ∈ SUCCESS/DENIED/FAILURE），队列满丢弃计数告警、文件 IO 故障限频告警，均不影响业务；审计明细禁止含密码/令牌/个人信息，键 ≤20、每值 ≤512 字符超值限截断并加 `…[truncated]` 标记、键数超限保留前 20 并加 `_truncated` 标记；Web 环境由 LogContextCleanupFilter 在请求结束时清除本组件 MDC 键（防线程池复用泄漏，不受审计开关约束）；AI 调用日志仅表结构预留（`deploy/sql/ai-call-log.sql`，V1.5 由 WBS 3.10.3 填充）。设计定稿见 `docs/designs/WBS-2.4.4-hifi.md`（PO 已确认）。

## 4. 理由与业务影响说明

**理由**：与 ADR-001 同生态的网关使配置对 AI 生成最友好、团队零额外学习面；接口规范与错误码体系必须先于所有服务冻结，否则 AI 并行开发会产生 N 套风格、common 组件（错误码/分页/鉴权）无从建起——这是"先立契约、后写代码"的最小前置。

- **成本/工期**：全部为既有工作包内容，无新增；
- **风险**：接口规范一旦冻结，变更走 ADR 流程——前期把规范定细可避免后续大量返工；
- **影响范围**：全部服务的接口层与网关；前端与连接器 SDK 均按本规范对接。

## 5. 可替换性评估（D-3）

REST/JSON 为最通用接口形态，网关替换（如 APISIX）只影响部署拓扑不影响契约；错误码与响应结构为自定契约，跨栈通用。风险等级：低。


## 6. 批准签发

| 角色 | 结论 | 日期 | 签名 |
| --- | --- | --- | --- |
| pm+po | 批准方案A | 2026:09:06:14:10| melody-C |