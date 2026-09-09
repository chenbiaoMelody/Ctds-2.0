# WBS-2.4.7 common-幂等与分布式锁 · 高保真设计
- 型态：非界面类（任务卡已标注）
- 对应规格：制度依据 =《C-TDS项目总体实施计划与WBS》2.4.7 产出定义（"幂等组件、锁组件 + 用例"，工作量 1 天）+ 章程 4.3（横切能力收敛 `common`）+ ADR-001（Redis 栈内）；方向确认 = `docs/designs/WBS-2.4.7-lofi.md`（PO 2026-09-09 签署，五问答复：1 认可、2 选 Redisson、3 选 B、4 认可、5 认可）
- 任务卡：WBS 2.4.7 ｜ 工作量：1 天
- 关联设计：本文件为高保真（= 编码契约）；低保真 = `docs/designs/WBS-2.4.7-lofi.md`
- 本文件新增契约（接口/错误码/配置/边界）将随编码同步固化进 **ADR-007-幂等与分布式锁契约**（沿 2.4.6 ADR-006 先例）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 待签署 | 项目主导者（兼任 PO） |  |  |

## 行为清单（12 项，逐条对应 lofi 已确认方向与计划测试）

| 编号 | 行为（业务可读） | lofi 出处 | 计划测试 |
| --- | --- | --- | --- |
> | B1 | 新建公共组件 `common/idempotency`（模块名=目录=artifactId=`common-idempotency`，与 auth/crypto 等平级）；依赖 `common-errorcode`、`common-logging`、`spring-boot-starter-aop`（官方 starter，注解切面运行时，BOM 管版本）；`redisson-spring-boot-starter:3.52.0` 以 **optional** 引入（沿 2.4.5 先例——防无 Redis 环境被 Redisson 自动配置牵连；演示/单测走内存模式，Redis 模式由引入方显式加依赖触发） | lofi 做什么-1 + 场景判定 | 编译 + 装配测试（双模式 Bean 条件生效） |
> | B2 | 幂等组件：`@Idempotent(key=SpEL 表达式, expireSeconds=结果 TTL)` 注解 + 切面——首次请求执行业务，业务成功后缓存结果；同幂等键重复请求不执行业务、直接返回首次结果（**模式 B 结果复用**，PO 选 B）；幂等键 = 全局前缀 + SpEL 求值结果 | lofi 做什么-2 + 待确认 3（B） | IdempotentAdviceTest：首次执行+结果缓存+重复返回首次结果（同参/不同参互不影响） |
> | B3 | 业务抛异常 → 释放执行权、不缓存结果，同幂等键可重试（结果只缓存成功） | lofi 做什么-2 | 测试：业务异常后重试成功（两次执行计数=1 次成功） |
> | B4 | 幂等键已被占用且尚无结果（业务仍在执行）→ 重复请求返回统一错误 `1002C0001`"请求处理中，请稍后重试"（调用方稍后重试） | lofi 做什么-2 + 边界 | 测试：慢业务 + 并发同键第二请求 → 1002C0001 |
> | B5 | 幂等键/结果 TTL 到期 → 允许重新执行（键粒度与 TTL 由使用方按业务配置；执行中标记 TTL 须 ≥ 业务最长执行时间，超长业务可能被重复执行——使用方责任，见边界表） | lofi 做什么-2/5 | 测试：expire 后同键重新执行（计数=2） |
> | B6 | 锁组件：`@Locked(key=SpEL 表达式, waitSeconds=等待超时, leaseSeconds=持锁时间)` 注解 + 切面——同互斥键并发调用只有一个进入，其余等待 waitSeconds，超时返回统一错误 `1002C0002`"操作繁忙，请稍后重试" | lofi 做什么-3 + 待确认 2（Redisson） | LockAdviceTest：并发 20 线程同键互斥（临界区计数恰=期望）+ 超时报错 |
> | B7 | 锁释放安全：业务异常 finally 释放（不泄漏锁）；默认 leaseSeconds=-1 走 Redisson 看门狗自动续期（防持锁方崩溃死锁）；释放校验持有者（Redisson 原生语义，误删他人锁不可能） | lofi 做什么-3 + 不做什么（锁协议不自研） | 测试：异常后锁已释放可再获取 + 持锁期间他人获取失败 |
> | B8 | 接口抽象 + 双实现（沿 auth"业务零改动可替换"边界）：`IdempotencyStore`、`LockService` 接口；**Redis 实现**（默认，production 语义；幂等=StringRedisTemplate SETNX+结果缓存+TTL，锁=Redisson RLock）+ **内存实现**（无 Redis 演示/单测/单机兜底；ConcurrentHashMap+TTL 清理 / ReentrantLock）；配置 `ctds.idempotency.mode`、`ctds.lock.mode` 切换 | lofi 做什么-4 + 待确认 1（认可） | 契约测试双实现同跑：tryAcquire 首次成功/重复失败/release 后可再获取/complete 后结果可取/过期可重取 |
> | B9 | 统一错误码：新模块段 `1002` 四码定稿（见错误码表），沿用 BizException + GlobalExceptionHandler 默认映射（C→400、S→500），**errorcode 组件既有码与行为零改动**；S 码出站统一"系统繁忙，请稍后重试"（沿 2.4.6 评审①P2-3 已确认口径） | lofi 做什么-5 + 待确认 5（认可） | 封套断言测试（码值+对外文案+不含内部细节） |
> | B10 | 审计联动（复用 2.4.4 AuditRecorder，沿 2.4.5 已确认口径"默认开启可配置关闭"）：幂等命中/处理中/锁超时/锁服务异常记审计事件；配置开关 `audit-enabled` | lofi 做什么-5 | 审计记录断言（命中/超时场景各 1 条，关闭后 0 条） |
> | B11 | example-service 端到端演示（沿 2.4.6 SecretNote 惯例，内存仓储 + 集成测试）：①"提交订单"端点——同一订单号重复提交只处理一次、返回首次结果（幂等演示）；②"扣减库存"端点——并发扣同一商品不超卖（锁演示）；演示走内存模式（零中间件依赖，剧本可执行） | lofi 做什么-7 + 待确认 4（认可） | 集成测试：重复提交幂等（处理计数=1）+ 并发扣库存（库存≥0） |
> | B12 | 依赖登记与契约固化：`redisson-spring-boot-starter:3.52.0` + `spring-boot-starter-aop` 登记 `docs/dependencies.md`（审批栏注明 PO 预授权 + lofi 确认记录），根 pom `dependencyManagement` 锁版 + 模块注册随编码提交；新建 ADR-007 固化契约（沿 ADR-006 先例） | lofi 场景判定 + 缺口声明-1 | 人工核对（文档级） |

## 接口契约（编码契约 = 本表定稿）

### 注解契约

**`@Idempotent`**（方法级；切面 `IdempotencyAdvice` @Around）

| 属性 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `key` | String | 是 | — | 幂等键 SpEL 表达式（方法参数上下文，如 `#order.orderNo`；求值结果为 null/空串 → 抛 `1000C0001` PARAM_INVALID，快速失败） |
| `expireSeconds` | long | 否 | 600 | 结果缓存有效期（秒）；到期后同键允许重新执行（执行中标记 TTL 用配置 `processing-ttl-seconds`，与结果 TTL 分离） |

**`@Locked`**（方法级；切面 `LockAdvice` @Around）

| 属性 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `key` | String | 是 | — | 锁键 SpEL 表达式（方法参数上下文，如 `#productId`；求值 null/空 → `1000C0001`） |
| `waitSeconds` | long | 否 | 3 | 获取锁等待超时（秒）；超时 → `1002C0002`"操作繁忙，请稍后重试" |
| `leaseSeconds` | long | 否 | -1 | 持锁自动释放（秒）；`-1` = 看门狗自动续期（Redisson 默认 30s 续期，持锁线程存活期间锁不过期） |

- 切面顺序（@Order）：幂等切面外层、锁切面内层——先判重、再互斥（两注解同用时语义确定）；锁/幂等异常均经 BizException 抛出，由 GlobalExceptionHandler 映射。
- 返回值序列化：方法返回值经 Jackson 序列化进结果缓存（返回类型反序列化还原）；返回 null 视为合法结果（缓存"完成"标记，重复请求返回 null）；返回值不可序列化 → `1002S0002`。V1.0 幂等方法返回值须为可 JSON 序列化类型（文档写明）。

### 接口契约

**`IdempotencyStore`**（com.ctds.common.idempotency）

| 方法 | 语义 | 双实现行为一致性（契约测试断言） |
| --- | --- | --- |
| `boolean tryAcquire(String fullKey, Duration ttl)` | 尝试获取执行权；仅首次成功（Redis：SETNX；内存：putIfAbsent） | 并发 20 线程同键 → 恰 1 成功；ttl 到期后再次成功 |
| `void complete(String fullKey, String resultJson, Duration ttl)` | 业务成功：写入结果并置完成态（TTL=结果有效期） | complete 后 `getResult` 可取且值一致 |
| `Optional<String> getResult(String fullKey)` | 读结果（完成态）；无 = empty | 未 complete = empty；complete 后 = 结果 JSON |
| `void release(String fullKey)` | 业务异常：释放执行权（删除键，允许重试） | release 后 `tryAcquire` 再次成功 |

**`LockService`**（com.ctds.common.idempotency.lock）

| 方法 | 语义 | 说明 |
| --- | --- | --- |
| `Optional<LockHandle> tryLock(String fullKey, Duration waitTime, Duration leaseTime)` | 尝试获取锁；waitTime 内成功 → 返回句柄，失败 → empty | leaseTime 负值 = 看门狗语义（Redisson）；内存实现 = ReentrantLock（可重入语义与 RLock 一致） |
| `LockHandle`（AutoCloseable） | `unlock()` 释放（仅持有者语义） | 切面 finally 释放；业务异常不泄漏 |

- 键组装：`fullKey = keyPrefix + SpEL 求值结果`（keyPrefix 默认 `ctds:idem:` / `ctds:lock:`，防业务键冲突）。
- 装配：`IdempotencyAutoConfiguration` / `LockAutoConfiguration`——`@ConditionalOnProperty(enabled, matchIfMissing=true)` 总开关；mode=redis（默认）配 Redis 实现 Bean（`@ConditionalOnMissingBean` 可替换，沿 auth/KeyProvider 边界）、mode=memory 配内存实现；Redis 实现 Bean 以 `@ConditionalOnClass` 兜底（引入方无 redisson 依赖时该 Bean 不装配，配合模式配置实现安全失败，见边界表）。

### 配置项（`ctds.idempotency.*` / `ctds.lock.*`）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `ctds.idempotency.enabled` | true | 幂等组件总开关（false 不注册切面与 Bean） |
| `ctds.idempotency.mode` | redis | `redis`=Redis 实现（默认，production 语义）/ `memory`=内存实现（单机语义，演示/单测/单机部署用） |
| `ctds.idempotency.key-prefix` | `ctds:idem:` | 幂等键前缀 |
| `ctds.idempotency.processing-ttl-seconds` | 60 | 执行中标记 TTL（须 ≥ 业务最长执行时间，否则超长业务可能被重复执行——使用方责任，见边界表） |
| `ctds.idempotency.default-expire-seconds` | 600 | 结果缓存默认 TTL（@Idempotent 未显式指定时） |
| `ctds.idempotency.audit-enabled` | true | 审计联动开关（命中/处理中记审计） |
| `ctds.lock.enabled` | true | 锁组件总开关 |
| `ctds.lock.mode` | redis | `redis`=Redisson（默认）/ `memory`=内存实现 |
| `ctds.lock.key-prefix` | `ctds:lock:` | 锁键前缀 |
| `ctds.lock.default-wait-seconds` | 3 | @Locked 未显式指定等待超时 |
| `ctds.lock.default-lease-seconds` | -1 | @Locked 未显式指定持锁时间（-1=看门狗） |
| `ctds.lock.audit-enabled` | true | 审计联动开关（超时/服务异常记审计） |

- Redis 连接：`spring.data.redis.*`（Redisson starter 自动接管；内存模式无需 Redis）。

### 错误码表（`IdempotencyErrorCodes` 常量类，新模块码段 1002，9 位格式沿 1000/1001 段）

| 码 | 常量 | 类型→HTTP | 对外文案 | 触发场景 |
| --- | --- | --- | --- | --- |
> | `1002C0001` | `IDEMPOTENCY_IN_PROGRESS` | C→400 | 请求处理中，请稍后重试 | 幂等键已占用且暂无结果（业务仍在执行） |
> | `1002C0002` | `LOCK_ACQUIRE_TIMEOUT` | C→400 | 操作繁忙，请稍后重试 | 锁等待超时（waitSeconds 内未获取） |
> | `1002S0001` | `IDEMPOTENCY_STORE_UNAVAILABLE` | S→500 | （出站统一"系统繁忙，请稍后重试"，沿 2.4.6 口径） | 幂等存储不可用（Redis 连接异常等；fail-closed：宁可拒绝不执行业务，防重复执行） |
> | `1002S0002` | `LOCK_SERVICE_UNAVAILABLE` | S→500 | （同上） | 锁服务不可用（fail-closed：宁可拒绝不放行，防并发破坏业务） |

- 全部经 `BizException` 抛出，沿用全局默认映射，errorcode 模块零改动；对外文案为服务端常量，不回显输入、不暴露内部实现。

### 依赖清单

| 坐标 | 版本 | 范围 | 说明 |
| --- | --- | --- | --- |
| `org.springframework.boot:spring-boot-starter-aop` | Boot 3.5.16 BOM 管理 | compile（非 optional） | 注解切面运行时（@Aspect + 自动配置；无副作用，存在 @Aspect 才生效） |
| `org.redisson:redisson-spring-boot-starter` | 3.52.0（根 pom dependencyManagement 锁版） | **optional** | 分布式锁 + 幂等 Redis 连接（自带 spring-boot-starter-data-redis）；Redis 模式引入方显式加依赖 |
| `com.ctds:common-errorcode` / `common-logging` | 2.0.0-SNAPSHOT | compile | 统一错误码 / 审计联动 |
| `org.springframework.boot:spring-boot-starter-test` | Boot BOM | test | 测试 |

## 边界值与异常行为

| 场景 | 行为 | 依据 |
| --- | --- | --- |
| SpEL 求值失败 / key 表达式为 null/空 | 调用时抛 `1000C0001` PARAM_INVALID（快速失败，不执行业务） | 注解契约 key 必填 |
| 幂等键重复且结果存在 | 不执行业务，反序列化返回首次结果（模式 B） | B2 |
| 幂等键重复且结果不存在（执行中） | 抛 `1002C0001` 请求处理中 | B4 |
| 业务异常 | 释放执行权（release），不缓存结果，允许重试 | B3 |
| 幂等键 TTL 过期 | 允许重新执行（结果缓存与执行中标记分开计 TTL） | B5 |
| 业务执行超过程序中标记 TTL | 执行中标记先过期 → 后续同键请求可能再次执行（**使用方责任**：把 processing-ttl-seconds 配到业务上限以上；文档写明） | 边界（V1.0 无看门狗式续期，避免过度设计） |
| 返回值 null | 视为合法结果，缓存完成态；重复请求返回 null | 契约 |
| 返回值不可 JSON 序列化 | 抛 `1002S0002`（失败即释放执行权，可重试） | 契约 |
| 锁等待超时 | 抛 `1002C0002` 操作繁忙（业务不执行） | B6 |
| 持锁期间业务异常 | finally 释放，锁不泄漏；他人可再获取 | B7 |
| 持锁线程崩溃 | Redisson 看门狗续期停止 → 锁自动过期释放（防死锁）；内存实现进程内无此场景（进程崩锁即失） | B7/不做什么 |
| 锁/幂等存储不可用（Redis 连接异常） | **fail-closed**：抛 `1002S0001/1002S0002`，不执行业务（防重复执行/防无锁放行） | 边界（安全默认） |
| mode=redis 但引入方无 redisson 依赖 | Redis 实现 Bean 条件不装配；调用时缺 Bean 启动失败（**安全失败**：显式错误提示配置，不静默降级内存） | 边界（防单机语义误用） |
| mode=memory 误用于多实例部署 | 组件文档与配置注释明示"内存实现=单机语义，多实例必须 redis"（配置层面无法强制，文档警告） | 边界（观察项，非本任务范围） |

## 变更影响声明

（本表随 4 视角评审处置结果增补，沿 2.4.6 hifi"评审修复记录"先例——评审处置留痕见本文件评审后修订版 + 编码会话日志）

## 规格缺口声明

1. 制度依据缺口：无（沿 lofi 缺口声明-1：本契约随编码固化进 ADR-007）。
2. 观察项（不阻塞本任务，沿 lofi 缺口声明-2）：① 2.4.11 Testcontainers 未建——Redis 实现契约测试在有真实 Redis 的集成环境跑、无则条件跳过，不为此引入嵌入式 Redis/Testcontainers；② Redis 哨兵/集群高可用属 2.5.x 部署基建，组件连接单节点、配置预留；③ 组件 enabled 开关语义收敛与 2.4.6 观察项同批处理（本组件沿既有惯例，不新起语义）。

## 问题确认：
（PO 签署本文件即视为 12 项行为清单 + 接口契约 + 错误码表 + 边界值全部确认；如需修改在签署意见中列明）
