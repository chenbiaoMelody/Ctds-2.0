# WBS-3.10.3 护栏框架与 AI 审计预留 · 高保真设计（编码契约草案）

| 项 | 内容 |
| --- | --- |
| 版本 | **V1.0（2026-11-18 编排师定稿一次确认，指示原文"定稿确认，三份转V1.0"）= 编码契约**；实现与本稿不一致 = 打回项；V0.9 = 设计段草案 |
| 任务卡 / 规格 / lofi | `docs/tasks/WBS-3.10.3-护栏框架与AI审计预留-2026-11-18.md`；`docs/specs/C-11.5-护栏框架与AI审计预留.md` **V1.0（已批准）**（B1~B4）；`docs/designs/WBS-3.10.3-lofi.md` **V1.0（方向定稿）** |
| 确认状态 | Q1~Q7 已确认采 A（2026-11-18"确认 Q1~Q7 均采 A"）；**定稿一次确认已完成**（2026-11-18"定稿确认，三份转V1.0"；§10 签署） |
| 技术基线 | JDK 17 + Maven（ADR-001 冻结）；父 POM `spring-boot-starter-parent:3.5.16`；构件版本 **2.0.0-SNAPSHOT**；parent `<relativePath>../../pom.xml</relativePath>`（AGENTS §3） |

## §1 模块与文件清单（新增面，精确）

模块目录 `common/guardrail/`，artifactId **`common-guardrail`**，包根 **`com.ctds.common.guardrail`**（沿 `com.ctds.common.modelgateway` 先例）。

| 文件（相对模块根） | 类型 | 职责 |
| --- | --- | --- |
| `pom.xml` | 构建 | parent ctds-platform；依赖 = `com.ctds:common-model-gateway`（编译）+ `org.springframework.boot:spring-boot-starter-test`（test，受管）；**零其他依赖** |
| `src/main/java/com/ctds/common/guardrail/api/GuardrailVerdict.java` | enum | 判定二值 `ALLOW / DENY` |
| `.../api/GuardrailDecision.java` | record | 判定结果（verdict + reasonCode）+ 工厂方法 |
| `.../api/GuardrailContext.java` | record | 调用上下文（feature / actor / traceId / provider）；缺省 `-` |
| `.../api/GuardrailInput.java` | record | 输入护栏入参（请求 + 上下文） |
| `.../api/GuardrailOutput.java` | record | 输出护栏入参（响应 + 上下文） |
| `.../api/InputGuardrail.java` | interface | 输入护栏扩展位 |
| `.../api/OutputGuardrail.java` | interface | 输出护栏扩展位 |
| `.../api/GuardrailStage.java` | enum | `INPUT / OUTPUT` |
| `.../api/GuardrailDeniedException.java` | exception | 拒绝异常（stage + reasonCode） |
| `.../engine/GuardrailChain.java` | class | 执行链：顺序、短路、空链直通、异常 fail-closed |
| `.../audit/AiCallOutcome.java` | enum | `SUCCESS / DENIED / FAILURE` |
| `.../audit/AiCallLog.java` | record | 审计记录（仅元数据；无内容字段） |
| `.../audit/AiCallLogRecorder.java` | interface | 记录器契约 + `NOOP` 常量 |
| `.../gateway/GuardedModelGateway.java` | class | `ModelGateway` 装饰器（输入前 / 输出后 / 审计） |
| `src/test/java/com/ctds/common/guardrail/GuardrailChainTest.java` | test | T1~T3 |
| `.../GuardedModelGatewayTest.java` | test | T4~T8 |
| `.../AiCallLogContractTest.java` | test | T9 |
| `.../AiCallLogDdlReserveTest.java` | test | T10 |
| `.../ModuleBoundaryTest.java` | test | T11 |

根 `pom.xml` 改动（**唯一既有构建文件改动**）：① `<modules>` +1 行 `<module>common/guardrail</module>`（置于 `common/vector-store` 之后）；② `<dependencyManagement>` +1 节 `common-guardrail`（`${project.version}`，沿 common 模块先例）。

`deploy/sql/ai-call-log.sql` 改动：结构定稿（§7）；**仍为预留文件，禁止 V1.0 建表/写入**。

**零改动面**：`common/model-gateway`、`common/vector-store`、全部 `services/`、前端、网关、`std-adapter`、`connector-sdk`、既有迁移脚本、门禁配置（`scripts/gates/*`）、CI workflow、ADR-003 / ADR-005 历史原文、既有 3.10.1 / 3.10.2 测试。

## §2 接口契约（签名级）

```java
package com.ctds.common.guardrail.api;

public enum GuardrailVerdict { ALLOW, DENY }

public record GuardrailDecision(GuardrailVerdict verdict, String reasonCode) {
    public static final String ALLOW_REASON = "-";
    public static final String DEFAULT_DENY_REASON = "GUARDRAIL_DENIED";
    public static GuardrailDecision allow();
    public static GuardrailDecision deny(String reasonCode);
    public boolean denied();
}

public record GuardrailContext(String feature, String actor, String traceId, String provider) {
    public static final GuardrailContext NONE = new GuardrailContext("-", "-", "-", "-");
    // 紧凑构造：null/空白字段 → "-"
}

public record GuardrailInput(ChatCompletionRequest request, GuardrailContext context) { }

public record GuardrailOutput(ChatCompletionResponse response, GuardrailContext context) { }

@FunctionalInterface
public interface InputGuardrail {
    GuardrailDecision check(GuardrailInput input);
}

@FunctionalInterface
public interface OutputGuardrail {
    GuardrailDecision check(GuardrailOutput output);
}

public enum GuardrailStage { INPUT, OUTPUT }

public final class GuardrailDeniedException extends RuntimeException {
    public GuardrailDeniedException(GuardrailStage stage, String reasonCode);
    public GuardrailStage stage();
    public String reasonCode();
}
```

```java
package com.ctds.common.guardrail.engine;

public final class GuardrailChain {
    public GuardrailChain(List<InputGuardrail> inputGuardrails,
                          List<OutputGuardrail> outputGuardrails);
    public static GuardrailChain empty();
    public GuardrailDecision checkInput(GuardrailInput input);
    public GuardrailDecision checkOutput(GuardrailOutput output);
}
```

```java
package com.ctds.common.guardrail.audit;

public enum AiCallOutcome { SUCCESS, DENIED, FAILURE }

public record AiCallLog(
        String traceId, String feature, String actor, String provider, String model,
        int promptTokens, int completionTokens, int latencyMs,
        AiCallOutcome outcome, String errorCode,
        GuardrailVerdict guardrailVerdict, String guardrailReason) {
    // 紧凑构造：字符串 null/空白 → "-"；负数 token/latency → 0；outcome 非空
}

@FunctionalInterface
public interface AiCallLogRecorder {
    AiCallLogRecorder NOOP = log -> { };
    void record(AiCallLog log);
}
```

```java
package com.ctds.common.guardrail.gateway;

public final class GuardedModelGateway implements ModelGateway {
    public GuardedModelGateway(ModelGateway delegate, GuardrailChain chain,
                               AiCallLogRecorder recorder);
    @Override
    public ChatCompletionResponse chat(ChatCompletionRequest request);
    public ChatCompletionResponse chat(ChatCompletionRequest request, GuardrailContext context);
}
```

契约不变量：

1. `GuardrailContext` 字段缺省/空白一律归一为 `-`；`GuardrailContext.NONE` = 四字段全 `-`；
2. `GuardrailDecision.allow().reasonCode()` = `-`；`deny(null/空白)` → `GUARDRAIL_DENIED`；
3. `GuardrailChain` 构造时防御性复制列表；null 列表/列表内 null 元素 → `IllegalArgumentException`；`empty()` = 两个空列表；
4. 护栏返回 null / verdict 为 null / 抛 `RuntimeException` → 判定 `DENY` + `GUARDRAIL_ERROR`（不吞异常信息、不记录原始内容）；
5. `GuardedModelGateway` 构造：null delegate → `IllegalArgumentException`；null chain → `GuardrailChain.empty()`；null recorder → `AiCallLogRecorder.NOOP`；
6. `GuardedModelGateway` 无状态、线程安全；记录器实现须自行保证线程安全。

## §3 装饰器时序（B1 / B2 核心）

| 步骤 | 行为 | 审计 |
| --- | --- | --- |
| 1 | 归一上下文（null → `NONE`）；开始单调计时 | — |
| 2 | 执行输入护栏链；首个 DENY 短路 | — |
| 3 | 输入 DENY → **不调用下游** | 记录 `DENIED`；tokens=0；verdict=DENY；reason；随后抛 `GuardrailDeniedException(INPUT)` |
| 4 | 调用下游 `delegate.chat(request)` | — |
| 5 | 下游抛 `RuntimeException` → **原异常原样抛出**（不包装） | 记录 `FAILURE`；tokens=0；verdict=ALLOW；errorCode=null；随后抛出原异常 |
| 6 | 执行输出护栏链；首个 DENY 短路 | — |
| 7 | 输出 DENY → **不返回响应** | 记录 `DENIED`；tokens 取响应 `usage`（null→0）；verdict=DENY；随后抛 `GuardrailDeniedException(OUTPUT)` |
| 8 | 全部放行 → 返回响应 | 记录 `SUCCESS`；tokens 取响应 `usage`（null→0）；verdict=ALLOW |
| 9 | 记录器抛 `RuntimeException` | **吞掉**；不影响上述返回/异常语义（契约"绝不抛错"由装饰器兜底） |

- `model` = `request.model()`（null/空白 → `-`）；`provider` = 上下文 `provider`；`feature` / `actor` / `traceId` 取上下文；
- `latencyMs` = `System.nanoTime()` 差值换算毫秒，负数归 0，超 `Integer.MAX_VALUE` 截顶。
- `chat(request)` = `chat(request, GuardrailContext.NONE)`。

## §4 AI 调用日志 DDL 定稿（B3；V1.0 不建表）

文件 `deploy/sql/ai-call-log.sql`，`CREATE TABLE IF NOT EXISTS ai_call_log`：

| 列 | 类型 | 约束 / 注释 |
| --- | --- | --- |
| `id` | BIGINT | PK，AUTO_INCREMENT |
| `trace_id` | VARCHAR(64) | NOT NULL DEFAULT '-'，索引 `idx_ai_call_log_trace` |
| `feature` | VARCHAR(64) | NOT NULL DEFAULT '-'（业务用途） |
| `actor` | VARCHAR(64) | NOT NULL DEFAULT '-'（调用主体） |
| `provider` | VARCHAR(64) | NOT NULL DEFAULT '-'（模型服务商） |
| `model` | VARCHAR(128) | NOT NULL DEFAULT '-'（模型标识） |
| `prompt_tokens` | INT | NOT NULL DEFAULT 0 |
| `completion_tokens` | INT | NOT NULL DEFAULT 0 |
| `latency_ms` | INT | NULL（耗时毫秒） |
| `outcome` | VARCHAR(16) | NOT NULL；`SUCCESS / DENIED / FAILURE` |
| `error_code` | VARCHAR(16) | NULL（V1.5 映射平台错误码） |
| `guardrail_verdict` | VARCHAR(16) | NULL；`ALLOW / DENY` |
| `guardrail_reason` | VARCHAR(64) | NULL（可读原因码） |
| `created_at` | DATETIME(3) | NOT NULL DEFAULT CURRENT_TIMESTAMP(3)，索引 `idx_ai_call_log_created` |

- **零内容列**：不得出现 prompt / messages / response / content / 密钥 / 个人信息字段；
- 文件头保留并更新声明：**"仅表结构预留。禁止在 V1.0 建表或写入；V1.5 由 C-8.4 / C-11.5 填充归属服务与 Flyway 实施。"**
- V1.0 零迁移：`services/*/src/main/resources/db/migration/` 不得新增 `ai_call_log` 相关脚本。

## §5 测试用例表（T1~T11，先红后绿；JUnit 5，离线，无 Spring 上下文，无 Testcontainers）

| 用例 | 覆盖 | 断言要点 |
| --- | --- | --- |
| **T1** 空链直通 | B1 | 空输入/输出链 → ALLOW；`empty()` 可用 |
| **T2** 输入拒绝短路 | B1 | 第二个输入护栏不被调用；下游 `ModelGateway` 零调用；异常 stage=INPUT、reason 原样 |
| **T3** 护栏异常 fail-closed | B1 | 抛 `RuntimeException` / 返回 null / verdict null → DENY + `GUARDRAIL_ERROR` |
| **T4** 输出拒绝 | B1 | 下游已调用；响应不返回；异常 stage=OUTPUT；记录 DENIED 且 tokens 取响应 usage |
| **T5** 成功路径 | B1/B2 | 下游恰一次；记录 SUCCESS、tokens、latency ≥0、verdict=ALLOW；`chat(request)` 使用 `NONE` 上下文 |
| **T6** 下游异常 | B1/B2 | 同一异常实例原样抛出；记录 FAILURE、verdict=ALLOW、errorCode=null |
| **T7** 记录器异常隔离 | B2 | 记录器抛 `RuntimeException` → 成功仍返回、拒绝仍拒绝、下游异常仍原样抛出 |
| **T8** 记录器默认与上下文归一 | B2 | null recorder → NOOP 不抛错；null/空白上下文字段 → `-`；usage null → 0 tokens |
| **T9** `AiCallLog` 契约 | B2 | record 组件恰好为契约字段；字符串归一、负数归 0、outcome 必填；**无内容字段** |
| **T10** DDL 预留边界 | B3 | 文件含 14 列与两索引；零内容列；文件头含"禁止在 V1.0 建表或写入"；全仓无 `ai_call_log` 迁移脚本 |
| **T11** 模块边界 | B4 | 主源零 `java.net` / `HttpClient` / Spring 导入；pom 生产依赖仅 `common-model-gateway`；测试离线 |

> T10 读取路径：surefire 默认工作目录 = 模块目录，测试以 `Path.of("..", "..", "deploy", "sql", "ai-call-log.sql")` 定位仓库内预留 DDL；断言文件头声明、14 列、两索引、零内容列，并扫描 `services/*/src/main/resources/db/migration/` 无 `ai_call_log` 脚本。

## §6 边界值与异常行为（统一表）

| 场景 | 行为 |
| --- | --- |
| 护栏返回 null / verdict null | DENY + `GUARDRAIL_ERROR`（fail-closed） |
| 护栏抛 `RuntimeException` | DENY + `GUARDRAIL_ERROR`；不记录原始异常内容 |
| delegate 抛 `RuntimeException` | 原样抛出（同一实例）；记录 FAILURE |
| delegate 抛 `Error` | 不捕获、不审计、直接传播（避免干扰致命错误） |
| recorder 抛 `RuntimeException` | 吞掉；主流程结果不变 |
| recorder 为 null | 使用 `NOOP` |
| chain 为 null | 使用 `empty()` |
| delegate 为 null | 构造即 `IllegalArgumentException` |
| context 为 null | 使用 `NONE` |
| request / usage 字段为 null | `model` → `-`；tokens → 0 |
| reasonCode 为 null / 空白 | `GUARDRAIL_DENIED` |
| latency 溢出 int | 截顶 `Integer.MAX_VALUE` |
| 并发调用 | 装饰器/链无共享可变状态，线程安全；recorder 线程安全由实现方保证 |

## §7 验证命令（编码段交付口径）

1. `mvn -B -ntp -pl common/guardrail test`（模块单测，T1~T11 红相 → 绿相）；
2. `mvn -B -ntp compile` / `mvn -B -ntp test`（全仓编译与测试）；
3. `mvn -B -ntp checkstyle:check -pl common/guardrail`（风格）；
4. `scripts/gates/run-gates.ps1` 全仓门禁单发（ConfigSha256 与基线逐字一致；coverage overall 不劣于 **92.43%**）；
5. 断网/零密钥人工核对：主源零网络 import、pom 零新增生产依赖、仓库零真实密钥。

## §8 零改动与诚实边界

1. 3.10.1 `common/model-gateway` / 3.10.2 `common/vector-store` 主源与测试零改动；
2. 无真实 `ModelGateway` 实现 → 装饰器为休眠态，不产生真实调用/审计数据；
3. 无真实护栏实现 → 不得宣称具备 prompt 注入检测等内容安全能力；
4. 无建表/落库/迁移 → 不得宣称"AI 审计已落库"；
5. 审计记录器仅元数据，零 prompt / 响应原文 / 密钥 / 个人信息（CAT-01 / L2）。

## §9 勘误位（评审/复审段回填）

（V0.9 空；评审与编码段发现的偏差、口径勘误在此登记，不改历史段落，按"勘误说明 + 影响范围"追加。）

## §10 确认记录

| 版本 | 日期 | 角色 | 说明 |
| --- | --- | --- | --- |
| V0.9 | 2026-11-18 | AI | 设计段草案：Q1~Q7 已确认采 A；规格/两级设计 V0.9 落盘，待一次确认 |
| V1.0 | 2026-11-18 | 编排师 | **"定稿确认，三份转V1.0"**（指示原文）= 编码契约生效；实现与本稿不一致 = 打回项 |
