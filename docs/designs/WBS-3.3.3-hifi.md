# WBS-3.3.3 高保真设计：元数据采集服务（hifi）

| 字段 | 内容 |
| --- | --- |
| 版本 | **V1.0（已确认 = 编码契约，实现须逐条一致）**——2026-10-01 编排师会话回复"**确认**"→ Q1~Q10 均采建议 A + D1 不拆分豁免；与 lofi 同批签署（章程 2.6.3 一次确认） |
| 日期 | 2026-09-30 |
| 任务卡 | `docs/tasks/WBS-3.3.3-元数据采集服务-2026-09-30.md` |
| lofi | `docs/designs/WBS-3.3.3-lofi.md`（Q1~Q10 + D1 全文） |
| 规格输入 | `docs/specs/C-3.1-2.3-数据目录与资源.md` V1.0 行为 1 规则 3/5、行为 2 规则 1、行为 7 规则 1/5 + §末未定义项 |
| 上游 | 3.3.2 `catalog-service`（`81542ce`）：V1 四表 / 5 端点 / `CatalogErrorCodes`（1007C0001~0008 + S0001/S0002）/ `SemanticTags` / `DatasetNameNormalizer`；3.2.5 V3 平台基线种子先例 |

> **本版已获一次确认（V1.0 = 编码契约）**：Q1~Q10 均采建议口径 A + D1 不拆分（2026-10-01 编排师"确认"）——正文按建议 A 书写的全部口径（含 Q1-A 不做"来源系统对接"、Q2-A 不改 `semantic_tags` 载体形态）**即为定稿，无需重写**；实现须与本文逐条一致，偏离即打回。

## 1. 端点契约表（2 新读端点 + 既有 2 写面校验升级）

### 1.1 新增读端点（catalog-service，前缀 `/api/v1`）

| # | 方法与路径 | 权限点 | 请求 | 响应 200 | 主要错误码 |
| --- | --- | --- | --- | --- | --- |
| R3 | `GET /tag-vocabularies` | `vocabulary.read` | 无（可扩展分页，本版词表册极少 → 全量清单） | `List<TagVocabularyView>`：`vocabularyCode`、`vocabularyName` | ——（未认证 401 / 无权限 403 沿平台口径） |
| R4 | `GET /tag-vocabularies/{vocabularyCode}/terms` | `vocabulary.read` | 路径 `vocabularyCode`；`keyword` 可选（词条名称模糊）；`pageNum`/`pageSize`（`common-pagination` 规范） | `PageResult<TagTermView>`：`termCode`、`termName`；按 `term_code` 升序稳定排序 | 1007C0010 词表不存在（404） |

> 端点编号续 3.3.2 的读面编号（R1 本人列表 / R2 本人详情）排为 **R3/R4**——**不占用 V1/V2 字样**（V1/V2 在本文档中保留给迁移版本与规格版本，避免混淆）；写面 W1/W2 为 3.3.2 既有端点，本卡只升级其校验链。

- 响应**仅词条元数据**：无数据本体、无主体信息、无个人可识别信息（行为 7 规则 5；§6 边界声明 3）；
- 权限点映射（catalog `application.yml` 1 行改动）：`provider: …,vocabulary.read`、`admin: dataset.read,vocabulary.read`——**不动既有 `dataset.*` 四点**；
- 读面**不做** ADMITTED 资格门槛（Q8-A 口径登记）；不引入枚举型"业务码"字段（术语沿 §6 边界声明 6）。

### 1.2 既有写面（**端点与入参零变更**，仅校验链升级）

| # | 端点（3.3.2 原样） | 本卡变更 |
| --- | --- | --- |
| W1 | `POST /data-spaces/{spaceId}/datasets` | 校验链第 6 步（归一化判重之前）插入**词条成员校验**：任一标签不匹配受控词表 → `1007C0009`（400）；载体级校验（必填/1~10 项/单条 ≤32/去重）不变 |
| W2 | `PUT /datasets/{datasetId}` | `validTagsJson()` 同款插入；留痕仍走 `dataset_action_log`（`semanticTags:from→to`），**不新增动作码** |

> **入参字段名仍为 `tags`**（3.3.2 hifi §1.1 契约口径）；路径、幂等键、权限点、错误码语义**均不变**——3.3.6 界面与既有剧本步骤零返工。
> 幂等行为不受影响：命中的重复提交仍返回首次结果（成员校验在幂等切面之前/之后均不改变"首次成功才落库"的结论——合法请求才可能留痕）。

## 2. 错误码表（CatalogErrorCodes 顺延，1007 段）

| 码值 | 常量 | 场景 | HTTP | 对应规格规则 |
| --- | --- | --- | --- | --- |
| 1007C0009 | TAG_TERM_NOT_IN_VOCABULARY | 语义标签不在受控词表内（登记 / 变更） | 400 | 行为 1 规则 3「受控词表选取」 |
| 1007C0010 | TAG_VOCABULARY_NOT_FOUND | 词表册不存在（读面路径码未命中） | 404 | 设计契约（读面边界） |

- 文案为服务端常量、不拼接用户输入（章程 4.3）；**错误信息不回显被拒标签原文**（防回显注入与原文外泄，沿"对外文案不拼接输入"口径）；
- 1007C0001~0008 与 S0001/S0002 **零改动、零复用**（不复用 1007C0008：名称问题与标签问题须可分辨，便于留痕与验收定位）。

## 3. 表结构与约束（迁移 `V2__create_tag_vocabulary.sql`，库 `ctds_catalog`，MySQL 8）

### 3.1 `tag_vocabulary`（词表册）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT AUTO_INCREMENT | PK | 技术主键 |
| vocabulary_code | VARCHAR(32) | NOT NULL, UNIQUE `uk_vocabulary_code` | 词表码（本版恒 `SEMANTIC_TAG`；可扩展多册） |
| vocabulary_name | VARCHAR(64) | NOT NULL | 词表名（展示） |
| created_at | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP | 公共字段（**无 updated_at**：V1.0 无维护写面，数据只插不改） |

### 3.2 `tag_term`（词条）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT AUTO_INCREMENT | PK | 技术主键 |
| vocabulary_id | BIGINT | NOT NULL | 所属词表（同库引用，逻辑关联） |
| term_code | VARCHAR(32) | NOT NULL, UNIQUE `uk_term_code` | 词条业务编号（`TT` + 4 位序号，沿 data_no/subject_no"业务编号与技术主键分离"先例） |
| term_name | VARCHAR(64) | NOT NULL | 词条名称（展示与落库的文本） |
| normalized_term | VARCHAR(64) | NOT NULL | 归一化词条名（**由应用侧经 `DatasetNameNormalizer` 产出**，匹配口径唯一来源） |
| created_at | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP | 公共字段 |

- 唯一索引：`uk_term_code`（词条编号）/ `uk_vocab_norm_term(vocabulary_id, normalized_term)`（同册归一化名唯一——**存储引擎原子层兜底**，防种子或后续维护重复词条）；
- 二级索引：无（数据量级 ~10 条，读面全表分页 + `keyword` 过滤）；待 3.3.4 引入分类过滤时按需增列增索引（§10 备忘）；
- 引擎 / 字符集 / 排序规则：InnoDB / utf8mb4 / `utf8mb4_0900_ai_ci`（**匹配口径前提**：`IN` 比对大小写不敏感，沿 3.3.2 V1 判重前提）；
- **V1 零改动**：不新增列、不改语义、不回填数据。

### 3.3 种子词条（V2 迁移 `INSERT`，内容待 Q4 定稿）

| term_code | term_name | 归一化 | 备注 |
| --- | --- | --- | --- |
| TT0001 | 金融 | 金融 | **3.3.2 走查 / 测试实际使用（必须包含）** |
| TT0002 | 普惠 | 普惠 | **同上** |
| TT0003 | 风控 | 风控 | **同上** |
| TT0004 | 医疗健康 | 医疗健康 | 建议集 |
| TT0005 | 交通出行 | 交通出行 | 建议集 |
| TT0006 | 政务 | 政务 | 建议集 |
| TT0007 | 企业服务 | 企业服务 | 建议集 |
| TT0008 | 统计分析 | 统计分析 | 建议集 |
| TT0009 | 公开数据 | 公开数据 | 建议集 |
| TT0010 | 脱敏数据 | 脱敏数据 | 建议集 |
| TT0011 | 信用 | 信用 | 建议集 |
| TT0012 | 地理空间 | 地理空间 | 建议集 |

> 词表内容为**业务事项**（Q4）：`TT0001~0003` 为既有演示数据必需项，`TT0004~0012` 为建议集；PO 可增删改（改本表即改迁移种子行）。

## 4. 校验链（写面，含本卡插入点）

### 4.1 登记（W1）顺序（3.3.2 hifi §4.2 原序 + 本卡插入）

1. 身份与权限点 → 2. 参数校验（400 通用码：名称/类型/简介/分类/级别/标签载体级）→ 3. 主体资格三态 → 4. 空间状态 + 成员门槛 → 5. 重要数据拒收 → **6′ 语义标签词条成员校验（新：不匹配 → 1007C0009，400）** → 7. 归一化 + 同空间判重 → 8. 取号 + INSERT + REGISTER 留痕 → 9. 返回视图

- 插入点选在"判重之前"的理由：标签是**入参内容合法性**问题（与名称校验同类），应在触碰任何库内状态（判重/取号/留痕）之前拒绝——保持"非法入参不产生任何库内副作用"的既有性质（3.3.2 §4.2 语义不变）；
- 成员校验为**同库本地判定**：无网络调用、无 UNAVAILABLE 分支、不新增 S 型错误码。

### 4.2 变更（W2）顺序

`load → 属主 → 终态 → 字段校验（含 6′ 词条成员校验）→ 级别收紧门槛 → 逐字段留痕 + UPDATE`

- 变更提交的标签集为**整份替换语义**（3.3.2 既有语义）：整份新标签都必须匹配受控词表；
- 既有资源若存有"词表外标签"（理论不可达——Q4-A 种子覆盖既有演示标签；若发生），其变更须整份提交合法标签，本卡**不做任何自动清洗**（Q6-A）。

### 4.3 匹配实现（口径唯一）

```
findUnmatched(vocabularyCode, tags):
  normalized = tags.stream().map(DatasetNameNormalizer::normalize).distinct().toList()
  matched    = repository.findNormalizedTerms(vocabularyCode, normalized)   // SELECT normalized_term ... WHERE vocabulary_id = ? AND normalized_term IN (...)
  return normalized - matched                                                // 差集 = 非受控词表标签
```

- 差集非空 → 1007C0009；**拒绝文案不含被拒标签原文**；
- 归一化复用 `DatasetNameNormalizer`（trim + 去控制字符 + 折叠空白含 U+3000），**不新建第二套归一化**（DB-30 族：不扩大重复面）；
- 大小写折叠由 DB 排序规则（`0900_ai_ci`）承担，应用层不额外 `toLowerCase`（保持单一口径）。

## 5. 测试计划（Testcontainers MySQL 8 实跑，沿 `SharedMySqlContainer` + root 建库 GRANT 先例）

| 组 | 用例 | 对应规格 / 契约 |
| --- | --- | --- |
| T1 迁移探针 | V2 两表列齐 / 列注释 / `uk_vocabulary_code`、`uk_term_code`、`uk_vocab_norm_term` 三唯一约束**反向探针**（重复插入必拒）/ 种子词条 12 条且 `TT0001~0003` 在内 | 设计契约 §3 |
| T2 词表读面 | 词表册清单；词条分页（`keyword` 过滤命中/空结果）；分页边界（`pageSize>100`、`pageNum<1` → 400）；`total` 与分页字段齐备 | 行为 7 规则 5 + ADR-005 分页 |
| T3 读面边界 | 未知 `vocabularyCode` → **1007C0010 / 404**（与"空结果 200"可分辨）；未认证 → 401；无 `vocabulary.read` 角色 → 403 | 设计契约 §1.1 |
| T4 成员校验正向 | 登记：标签全在词表（含全半角/首尾空白/大小写差异写法）→ 成功、落库为**提交原文**、`data_no` 正常生成、REGISTER 留痕 | 行为 1 规则 3 |
| T5 成员校验反向（**核心锚**） | 登记：含 1 条词表外标签 → **1007C0009**、**零资源行、零留痕、零取号**（与"拒绝即无副作用"性质一致）；变更同款；错误文案**不含**被拒标签原文 | 行为 1 规则 3 |
| T6 变更联动 | 本人变更标签为合法集 → `from→to` 留痕逐字；提交整份含非法项 → 拒绝且**既有行标签不变**（无部分写入） | 行为 2 规则 1 |
| T7 既有数据兼容 | 插入 3.3.2 走查同款记录（标签 `金融/普惠/风控`）后，对其执行变更（合法标签集）→ 通过（证明种子集覆盖既有演示数据） | Q4-A / Q6-A |
| T8 领域单测 | `TagTerm` / `TagVocabulary` 构造与校验、词条编号格式（`^TT\d{4}$`）、归一化差集算法（多标签/重复输入/空输入）、枚举（若有）封闭性 | 设计契约 |
| T9 回归（**不降级**） | `catalog-service` 既有 **58/58 全量回归**（写面校验链加固后判定零变更，含 T15 并发/边界与双 client 用例） + 两模块 `checkstyle:check` 0 违规 | 既有保护 |
| T10 一致性锚 | 新码值（1007C0009/0010）↔ `CatalogExceptionHandler` HTTP 映射一致性锚（沿 3.3.2 `CatalogExceptionHandlerCodeConsistencyTest` 先例） | 设计契约 §2 |

> 覆盖率达标（行 ≥70% / 变异）归 **3.3.7**；本卡只保证"新增行为每条都有正反向用例、既有用例只增不减"。
> 预估用例增量 **20~28 例**（既有 58 → 78~86）。

## 6. 数据分级落级（Q10-A 执行）

| 数据 | CAT | 级别 | 理由 |
| --- | --- | --- | --- |
| `ctds_catalog.tag_vocabulary` / `tag_term`（受控词表） | CAT-03 | **L1** | 公开目录词汇（登记界面人人可见的选词字典），不含主体信息、个人信息与数据本体；参照规范 §6.2"数据集登记条目 L1（公开目录）"口径 |

- 回写：分级规范 §6.1 补行（1~2 行，视两表是否合并成行列示）；迁移头注同步标注。

## 7. 部署清单

- **零 deploy 变更**：`catalog-service` 已于 3.3.2 入列（deployment / service / kustomization / runbook / 建库脚本）；V2 迁移随服务自带（Flyway 启动即应用）；
- 演示期仅本机可达（ADR-016 §2.7）：不加网络策略、不改回环绑定（catalog yml `server.address: 127.0.0.1` 保持）。

## 8. 模块骨架（改动面 = catalog-service 模块内）

```
services/catalog-service
├─ src/main/java/com/ctds/catalog/
│   ├─ interfaces/TagVocabularyController.java          【新增】R3/R4 两端点 + TagVocabularyView/TagTermView
│   ├─ application/DatasetCommandService.java           【改 2 处】create()/validTagsJson() 插入词条成员校验
│   ├─ application/TagVocabularyQueryService.java       【新增】词表读面编排
│   ├─ domain/TagVocabulary.java / TagTerm.java         【新增】实体
│   ├─ domain/TagTermPort.java                          【新增】读面 + 成员校验差集端口（端口-适配器，沿 SubjectAdmissionPort 先例）
│   ├─ domain/CatalogErrorCodes.java                    【改】顺延 1007C0009 / 1007C0010
│   ├─ domain/SemanticTags.java                         【改注释】"词表归 3.3.3"承接兑现（行为不变）
│   └─ infrastructure/JdbcTagTermRepository.java        【新增】JdbcClient 实现（IN 查询 + 分页）
├─ src/main/resources/application.yml                   【改 1 行】provider/admin 角色映射加 vocabulary.read
└─ src/main/resources/db/migration/V2__create_tag_vocabulary.sql  【新增】两表 + 种子 12 条
```

- **零跨服务改动**：subject / space / did / kms / std-adapter **一个文件都不动**（对比 3.3.2 的 space 1 端点 + 2 处 yml——本卡更干净）；
- 不引新依赖（Jackson / JdbcClient / common 组件均已在位）；不改门禁配置；不改 ADR。

## 9. 剧本与文档衔接

- **C-3.1 剧本**：S1 步骤 4 / S2 步骤 1 判定不变（"受控词表选取"由字面变为真实实现）；**新增步骤**（Q9-A①）与 **C-3.2 前提⑤措辞校正**（Q9-A②）**待 PO 批准后生效**（只动归属表述与新增一步，业务判定一字不改）；
- 界面入口仍占位（3.3.6）；3.3.4 检索过滤可消费本卡词表（其类目树与本卡词表为**两套受控集合**，不得混用术语）。

## 10. 勘误与登记备忘（供评审与下游）

1. **WBS 行 265"来源系统对接"**：本卡按 Q1-A 不实现，登记为规格缺口（详见任务卡"规格缺口"行）——**下游承接方（3.3.4~3.3.7）不得据此自行实现**；
2. **台账口径校正**：台账「下一包」原记 3.3.3 承接"受控类目树载体"——**类目树归 3.3.4**（规格行为 5 规则 3 明示），本次随立卡校正为"受控**词表**载体"；
3. **C-3.2 剧本前提⑤**：把"语义标签词表"记在 3.3.4 名下，实际归 3.3.3（Q9-A② 校正建议已随卡呈报）；
4. **3.3.2 hifi §10.1 备忘**（"届时如改关联表结构走变更流程"）：本卡 Q2-A **不改**结构，该备忘继续有效；
5. **DB-30 族**：本卡显式复用 `DatasetNameNormalizer`，不新增归一化副本；只读 client 副本问题与本卡无关（本卡零跨服务调用）；
6. **3.3.4 备忘**：若需按类目过滤词条，`category` 列与索引随其迁移增补（本卡不预置未用列）；词表册可多册（`vocabulary_code` 已预留）；
7. **演示数据**：既有 `ctds_catalog` 走查数据（空间 28 + 3 条资源 + 留痕 12 + 名称锁 1）按 3.2.7 口径留置，随"下次真机演示/走查前统一清理"处置；**本卡上线不回填、不清洗既有行**。