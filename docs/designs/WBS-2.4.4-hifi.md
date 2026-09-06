# WBS-2.4.4 common-日志与审计埋点 · 高保真设计（定稿 = 编码契约）
- 型态：非界面类（任务卡已标注）
- 对应规格：制度依据 = WBS 2.4.4 产出定义 + 章程 4.3 + ADR-005（本定稿确认后随编码增补 §3"日志与审计契约"）+ 低保真（已确认 2026-09-06）
- 任务卡：WBS 2.4.4 ｜ 工作量：1 天
- 关联设计：低保真 = `docs/designs/WBS-2.4.4-lofi.md`（已确认：① 引入 logstash-logback-encoder；② 审计事件先落 JSONL 文件）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 待确认 | | | |

## 与低保真的差异声明（细化产生的唯一结构性调整）

低保真写"`JsonLogSetup` 自动装配、业务零配置生效"。细化为：**采用 Spring Boot 3.5 内置结构化日志**（`logging.structured.format.console/file: logstash`，底层正是已批准的 logstash-logback-encoder），每个服务加 3 行 yml 即生效，**组件不自研编码器**。理由：框架内置写法最主流、零自研维护、组件代码更少；效果与低保真描述一致（JSON 一行一条、MDC 字段自动入 JSON）。

## 行为清单（逐条对应产出定义与确认记录）

| 编号 | 行为（业务语言） | 对应依据 | 计划测试 |
| --- | --- | --- | --- |
| B1 | 服务按约定加 3 行配置后，控制台与应用日志文件（`logs/app.log`）输出"一行一条 JSON"，含时间、级别、线程、消息、MDC 字段（traceId 等） | 低保真做什么-2 | OutputCapture 捕获控制台 + JSON 解析断言 |
| B2 | `LogContext` 写错误码/业务模块进日志上下文；JSON 日志出现对应字段；清除后字段消失；traceId 沿用既有 `ApiResult.TRACE_MDC_KEY`（不重复造键） | 低保真做什么-1/2 | LogContext 单测（MDC + ListAppender） |
| B3 | `AuditEvent` 事件模型：缺 action 或 outcome 为空 → 抛 `BizException(PARAM_INVALID)`；actor 缺省记 "anonymous" | 低保真做什么-3 | AuditEvent 单测 |
| B4 | `AuditRecorder.record(event)` 异步落审计文件：`logs/audit-YYYY-MM-DD.jsonl`，一行一条 JSON（字段见数据结构）；跨天自动切换新文件 | 低保真做什么-3 + 确认问题2 | 注入 Clock 的临时目录单测 |
| B5 | 审计队列满（默认 10,000）时：`record` 不阻塞、不抛错，事件丢弃并计数；首丢与每第 100 次丢打 WARN（进主日志） | 低保真主要流程-4 | 小容量队列单测 |
| B6 | 审计文件写入失败（磁盘/权限）：不影响业务，错误进主日志（限频：每 60 秒最多 1 条 ERROR） | 低保真做什么-3（不拖慢业务） | 不可写目录单测 |
| B7 | example-service 接入：POST /api/v1/greetings 成功 → 审计文件出现 `greeting.create` SUCCESS 事件；GET 列表传非法 orderBy → 出现 DENIED 事件且该请求 JSON 日志含 errorCode | 低保真做什么-5 | @SpringBootTest + 临时审计目录 + OutputCapture |
| B8 | AI 调用日志表结构占位：`deploy/sql/ai-call-log.sql`（文件头声明"V1.0 预留、禁止建表使用"，V1.5 由 WBS 3.10.3 填充） | WBS 2.4.4"含 AI 调用日志预留" | 人工核对（文档级） |
| B9 | 依赖登记：新建 `docs/dependencies.md`（依赖登记簿）并登记 logstash-logback-encoder 8.1 的核验与审批记录 | 章程 3.5 / AGENTS.md 3 | 人工核对（文档级） |

## 接口契约

### LogContext（com.ctds.common.logging，静态工具）

| 方法 | 行为 |
| --- | --- |
| `setErrorCode(String code)` | 写入 MDC 键 `errorCode`；null/空 → 清除该键 |
| `setModule(String module)` | 写入 MDC 键 `module`；null/空 → 清除 |
| `clear()` | 清除本组件全部键（**不动** traceId——归 TraceIdFilter 管） |

MDC 键约定：`traceId`（既有，常量取自 `ApiResult.TRACE_MDC_KEY`）、`errorCode`、`module`。MDC 无值的键不出现在 JSON 中。

### AuditRecorder（接口）与 AuditEvent

```java
public interface AuditRecorder {
    void record(AuditEvent event);   // 任何情况下不抛异常、不阻塞
}

public record AuditEvent(
        String eventId,        // UUID；业务可缺省，由组件补填
        Instant eventTime,     // UTC；业务可缺省，由组件补填
        String actor,          // 谁；缺省 "anonymous"（鉴权组件 2.4.5 就绪后接真实身份）
        String action,         // 做什么；必填，命名"对象.动作"小写点分，如 greeting.create
        String targetType,     // 对什么（类型）；可空
        String targetId,       // 对什么（标识）；可空
        AuditOutcome outcome,  // 结果：SUCCESS / DENIED / FAILURE
        Map<String, String> detail // 明细；可空；防御性拷贝为不可变
)
```

- 默认实现 `AsyncFileAuditRecorder`（AutoConfiguration 注册为 `AuditRecorder` Bean）：单后台守护线程 + 有界队列（默认 10,000）+ Jackson 写 JSONL；停止时优雅排空（最多等 5 秒）。
- 配置项：`ctds.audit.enabled`（默认 true）、`ctds.audit.file-dir`（默认 `logs`）、`ctds.audit.queue-capacity`（默认 10000）。

### JSONL 审计行与服务 JSON 日志字段

审计行（`logs/audit-YYYY-MM-DD.jsonl`）：`eventId, eventTime(ISO-8601 UTC), service(取 spring.application.name), actor, action, targetType, targetId, outcome, detail, traceId`。
服务日志 JSON 行（Spring Boot 内置 logstash 格式）：`@timestamp, level, thread, logger, message` + MDC 字段（traceId/errorCode/module，有值才出现）。

## 数据结构与边界值

| 项 | 值/规则 |
| --- | --- |
| 审计文件路径 | `{ctds.audit.file-dir}/audit-YYYY-MM-DD.jsonl`（相对工作目录，按天滚动，保留策略随部署侧采集，组件不删文件） |
| detail 约束 | 键 ≤20 个、每值 ≤512 字符，超限截断并加 `…[truncated]` 标记（审计可用性优先，不因明细过大失败）；**禁止放入密码、令牌、个人信息**（评审②核对项） |
| 依赖 | `net.logstash.logback:logstash-logback-encoder:8.1`（Maven Central 核验 2026-09-06，Apache-2.0；9.0 需 Jackson 3 故不采用）+ `spring-boot-starter`（日志门面）、`spring-boot-autoconfigure`（自动装配）、`common-errorcode`（复用 ErrorCode/TraceIdFilter 约定）；pom 版本经根 dependencyManagement 统一管理 |
| 模块归属 | `common/logging`，artifactId `common-logging`，自动装配注册文件 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（沿用 errorcode 模式） |
| ADR 增补 | ADR-005 §3 增补第 6 项"日志与审计契约"（本页契约表+边界值精要），随编码任务提交 |

## 编码会话实施清单（冷启动后按序执行）

1. ADR-005 §3 增补"日志与审计契约"；2. 新建 `docs/dependencies.md` 并登记 8.1（B9）；3. 测试先行：按 B1–B7 写失败测试（含 B5/B6 异常路径，禁止仅 happy-path）；4. 实现 `common/logging`（模块名=目录=artifactId 一致，T10 口径）；5. example-service 接入（3 行 yml + create/list 审计点）；6. 门禁（编译/单测/checkstyle）全绿 → 4 视角评审 → 冒烟演示（GET/POST /api/v1/greetings 后看控制台 JSON、logs/audit-*.jsonl）。

## 变更影响声明

无验收剧本（基建组件，冒烟即演示）；追溯矩阵更新：WBS 2.4.4 → lofi/hifi → ADR-005 §3.6 → 代码/测试 → 依赖登记簿；不影响已通过评审的 errorcode/pagination 契约（errorcode 仅新增依赖引用，不改既有行为）。
