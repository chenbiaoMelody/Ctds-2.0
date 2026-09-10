# WBS-2.4.8 std-adapter 模块骨架 · 高保真设计
- 型态：非界面类（任务卡标注：基建骨架，无界面产出）
- 对应规格：制度依据 =《C-TDS项目总体实施计划与WBS》2.4.8 产出定义（"标准适配层骨架 + 互联互通接口占位"，工作量 1 天）+ 章程 4.3 + WBS §1.7 强依赖 + PRD §5.9/§6.9 + ADR-002/005；方向确认 = `docs/designs/WBS-2.4.8-lofi.md`（PO 2026-09-10 签署，五问答复：1 认可、2 认可、3 认可、4 认可、5 认可）
- 任务卡：WBS 2.4.8 ｜ 工作量：1 天（章程 2.6.3：两级一并提交、一次确认）
- 关联设计：本文件为高保真（= 编码契约）；低保真 = `docs/designs/WBS-2.4.8-lofi.md`
- 本文件新增契约（模块边界/域清单/错误码段/占位规则）将随编码同步固化进 **ADR-008-标准适配层契约**（沿 ADR-006/007 先例）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 确认 | 项目主导者（兼任 PO；会话回复"1 认可；2 认可；3 认可；4 认可；5 认可"，lofi+hifi 两级一并一次确认，表格由 AI 按该声明代录留痕） | 2026-09-10 | 无 |

## 行为清单（7 项，逐条对应 lofi 已确认方向与计划测试）

| 编号 | 行为（业务可读） | lofi 出处 | 计划测试 |
| --- | --- | --- | --- |
| H1 | 新建根目录 Maven 库模块 `std-adapter/`（目录=artifactId=`std-adapter`，无启动类无端口，与 common 组件同级）；父 pom 注册 `<module>std-adapter</module>` + `dependencyManagement` 登记 `com.ctds:std-adapter:${project.version}`；模块依赖仅 `common-errorcode` + `spring-boot-starter-test`（test），**零新外部依赖**（dependencies.md 无需登记） | lofi 做什么-1 | 编译 + 门禁 GREEN |
| H2 | 能力域模型：`enum StdDomain`（三值：`INTERCONNECT`互联互通 / `DID_INTEROP`DID互认 / `EVIDENCE`测评证据，各带中文名与"尚未开放"说明文案常量）；`record StdDomainStatus(StdDomain domain, boolean implemented, String message)` 域状态 | lofi 做什么-2/3 + 结构组成 | StdDomainStatusTest（构造校验 + 三域枚举完整性） |
| H3 | 占位接口：基础接口 `StdDomainApi { StdDomain domain(); StdDomainStatus status(); }`；三域各一个标记子接口（`InterconnectStandardApi` / `DidInteropStandardApi` / `EvidenceStandardApi`，骨架内不新增方法——**具体协议方法签名待后续工作包按信通院规范文本冻结**）；行为集中在基础接口，避免三接口重复定义（评审③视角） | lofi 做什么-3 + 缺口声明-1 | 编译 + H4 测试 |
| H4 | 占位实现：每域一个 `Placeholder...Api` 类（普通 Java 类，无 Spring 注解），`status()` 返回 `implemented=false` + 域专属"尚未开放"说明（含后续工作包指引）；`domain()` 返回对应枚举 | lofi 做什么-3 + 流程-1 | PlaceholderApisTest（三占位实现状态断言：域映射正确、implemented=false、message 非空） |
| H5 | 统一错误码段 1003：`StdAdapterErrorCodes.NOT_IMPLEMENTED = ErrorCode.of("1003C0001")`（对外文案"该标准互联功能尚未开放"，C 型→HTTP 400 默认映射，不暴露内部实现）；辅助工厂 `notImplemented(StdDomain)` 返回携带该码的 `BizException`（domain 仅用于内部日志参数校验，**不拼入对外文案**——对外文案三域统一），供下游实现"能力未交付即被调用"场景 | lofi 做什么-4 + 待确认 3 | StdAdapterErrorCodesTest（码值/类型位/文案断言 + null 域快速失败） |
| H6 | example-service 演示接线：pom 引入 `std-adapter`；infrastructure 配置类注册三个占位 Bean；application 服务汇总三域状态；`GET /api/v1/std-capabilities` 返回 `ApiResult<List<StdCapabilityView>>`（view 字段：code/name/implemented/message），鉴权口径与既有演示端点一致（只读） | lofi 做什么-5 + 待确认 4 | StdCapabilitiesIntegrationTest（封套 code="0" + 三域齐全 + 全部 implemented=false + 含 traceId） |
| H7 | 契约固化：新建 `docs/adr/ADR-008-标准适配层契约.md`——①模块边界与"标准相关实现一律收口 std-adapter"纪律（章程 4.3 落点）；②三域清单与新增域 = ADR 变更；③1003 错误码段占用声明；④占位替换规则（后续任务卡在对应域内以新实现替换 Placeholder，走各自设计/评审链，不改基础接口语义）；⑤互联互通契约测试自动轨（M2）落点 = evidence 域 | lofi 做什么-6 + 流程-3 | 人工核对（文档级） |

## 接口契约（编码契约 = 本表定稿）

### 类型清单（`com.ctds.std` 包根 + 三子包）

| 类型 | 位置 | 契约 |
| --- | --- | --- |
| `StdDomain`（enum） | `com.ctds.std` | 三值固定：`INTERCONNECT("interconnect","互联互通")`、`DID_INTEROP("did","跨空间身份互认")`、`EVIDENCE("evidence","测评证据")`；字段 code（稳定字符串，进 API 响应）、displayName（中文）；"尚未开放"定稿文案由占位实现在 status() 内联返回、测试以字面量钉死（评审③P3-3 措辞对齐；见下表） |
| `StdDomainStatus`（record） | `com.ctds.std` | `(StdDomain domain, boolean implemented, String message)`；紧凑构造器：domain/message 非 null、message 非空白，违反 → `IllegalArgumentException`（内部防御，骨架内调用点全部常量化，不可达 Web 层） |
| `StdDomainApi`（interface） | `com.ctds.std` | `StdDomain domain()`；`StdDomainStatus status()`——"这个标准域今天什么状态"的唯一探活契约 |
| `InterconnectStandardApi`（interface） | `com.ctds.std.interconnect` | `extends StdDomainApi`，骨架内无新增方法；javadoc 锚定 C-9.1/C-9.2、WBS 4.x 收口位置 |
| `DidInteropStandardApi`（interface） | `com.ctds.std.did` | `extends StdDomainApi`，同上；javadoc 锚定政务 CA 3.1.4、跨空间身份互认 3.1.10、智能体互认 C-9.3 |
| `EvidenceStandardApi`（interface） | `com.ctds.std.evidence` | `extends StdDomainApi`，同上；javadoc 锚定测评证据采集与互联互通契约测试自动轨（章程 G1） |
| `PlaceholderInterconnectStandardApi` 等三个 | 各域包内 | 实现对应子接口；纯常量返回，无状态、无配置、无网络访问 |
| `StdAdapterErrorCodes`（final 类） | `com.ctds.std` | `NOT_IMPLEMENTED = ErrorCode.of("1003C0001")`（对外文案"该标准互联功能尚未开放"）；`NOT_IMPLEMENTED_MESSAGE` 公开文案常量（沿 2.4.7 `IDEMPOTENCY_IN_PROGRESS_MESSAGE` 先例，评审①P3-1 登记）；`static BizException notImplemented(StdDomain domain)`：domain 为 null → `IllegalArgumentException`（编程错误快速失败）；BizException 对外文案固定为码表文案，不拼接域信息 |

### 占位实现状态值（message 定稿，业务语言）

| 域 | message |
| --- | --- |
| 互联互通 | `该能力域尚未开放：区域枢纽对接与产品互挂接口将按信通院互联互通规范由后续工作包实现（WBS 4.x）` |
| DID 互认 | `该能力域尚未开放：政务 CA 接入与跨空间身份互认接口将由后续工作包实现（WBS 3.1.4 / 3.1.10）` |
| 测评证据 | `该能力域尚未开放：测评证据采集与互联互通契约测试将由测评演练工作包实现（M2）` |

### 演示端点契约（example-service）

- `GET /api/v1/std-capabilities`（只读、无请求体、无参数）→ `ApiResult` 封套（code="0"，traceId 有值）；
- `data` = 三域状态数组（顺序固定：INTERCONNECT → DID_INTEROP → EVIDENCE），`StdCapabilityView(code, name, implemented, message)`；
- 依赖方向（ArchUnit 兼容）：interfaces → application → std-adapter 类型；占位 Bean 注册在 infrastructure（example-service 自身分层不破）；
- 鉴权：与既有演示端点（如幂等/锁演示）同口径，测试随实现补齐。

## 边界值与异常行为

| 场景 | 行为 |
| --- | --- |
| 对占位实现调用 `status()` | 永不抛错（纯内存常量）；骨架期 `implemented` 恒为 false，**无配置开关可翻转**（防误以为"开启即可用"——启用能力 = 后续工作包交付新实现，非配置动作） |
| 下游误调用未交付能力（使用 `notImplemented()` 时） | 抛 `BizException(1003C0001)` → GlobalExceptionHandler 默认映射 HTTP 400，封套 message="该标准互联功能尚未开放"（不含域名/类名/内部实现） |
| `notImplemented(null)` / `StdDomainStatus` 非法入参 | `IllegalArgumentException` 快速失败（编程错误，非业务态） |
| 新标准域需求（如未来"连接器协议域"） | 禁止就地加枚举值——走 ADR-008 变更 + 新任务卡（域清单 = 契约） |

## 测试计划（映射 H1–H7）

1. `StdDomainStatusTest`：record 构造校验（null/空白拒绝）+ 枚举三值完整性（code/displayName 非空）；
2. `PlaceholderApisTest`：三占位实现逐一断言 `domain()` 映射、`implemented=false`、message 等于定稿文案；
3. `StdAdapterErrorCodesTest`：码值 `"1003C0001"` 格式与类型位（C）；`notImplemented(域)` → BizException 码/文案一致；null → 快速失败；
4. `StdCapabilitiesIntegrationTest`（example-service，MockMvc）：GET → 200、封套 code="0"、data 三条、顺序与内容=上表定稿；
5. 门禁：`scripts/gates/run-gates.ps1`（compile + test + checkstyle）GREEN 后提交评审。

## 复用声明（检索过程）

- 错误码/异常/封套：复用 `common-errorcode`（ErrorCode/BizException/ApiResult/GlobalExceptionHandler），不新增异常类；
- 模块与文档惯例：pom 结构沿 `common/idempotency`（parent relativePath 写法）、演示接线沿 2.4.5/2.4.6/2.4.7 例（example-service 集成测试）、ADR 沿 006/007 先例、设计文档沿本目录既有格式；
- 规格外实现声明：无（本设计全部条目可回溯到 WBS 2.4.8 产出定义 + 章程/WBS/PRD/ADR 条文）。
