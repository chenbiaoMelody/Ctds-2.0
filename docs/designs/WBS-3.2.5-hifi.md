# WBS-3.2.5 策略继承引擎 · 高保真设计（编码契约）

> 任务卡：`docs/tasks/WBS-3.2.5-策略继承引擎-2026-09-27.md`｜低保真：`docs/designs/WBS-3.2.5-lofi.md`｜规格：`docs/specs/C-2.1-2.3-逻辑空间管理.md` V1.0
> **本文即编码契约**：实现与本文件不一致 = 打回项（章程 2.6）。

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 项 | 内容 |
| --- | --- |
| 编排师确认 | **已确认（2026-09-27 16:3x 会话回复"都按建议"）：Q1~Q6 均采建议 A + D1 不拆分**——本文转**编码契约**，实现与本文件不一致 = 打回项 |
| 确认时间 | 2026-09-27 16:3x 签署；**实现已回填（2026-09-27 编码会话，实现批 `dd5e7ed`，T1~T17 全绿 132/132 + checkstyle 0 违规）；4 视角评审修复批回填（2026-09-27 评审会话，§8 增 T18/T19 + §11 勘误记录 E1~E4，T1~T19 全绿 135/135 + checkstyle 0 违规）** |

## 1. 端点契约表（REST；平台面沿 ADR-005 复数资源命名独立顶层，空间面挂既有 `/api/v1/data-spaces` 前缀；响应统一 ApiResult 封套）

| # | 端点 | 权限判定 | 请求体 → 出参 | 规格锚点 |
| --- | --- | --- | --- | --- |
| 1 | `POST /api/v1/platform-policies` | `platform.policy`（角色头经 yml 映射，平台面无空间上下文） | `{entryKey, entryValue, redline}` → `PolicyEntryView`（PLATFORM 行，scope_uniq=0） | 行为 7 规则 1/6；Q6 |
| 2 | `PUT /api/v1/platform-policies/{entryId}` | `platform.policy` | `{entryValue?, redline?}`（至少一项；键不可变更） → `PolicyEntryView` | 行为 7 规则 4 |
| 3 | `GET /api/v1/platform-policies` | `platform.policy`（治理面只读） | `?pageNum&pageSize` → `PageResult<PolicyEntryView>`（含键/值/红线/状态/时间） | 行为 7 规则 1（来源权威面） |
| 4 | `POST /api/v1/data-spaces/{id}/policies/overrides` | `space.admin`（承接项③）+ **空间须 ACTIVE** | `{entryKey, entryValue}` → `PolicyOverrideView{entryKey, effectiveValue, source, provenance, note, platformValue, spaceValue, redline}`（生效结果与来源标注即时回显） | 行为 7 规则 2/3/4 |
| 5 | `GET /api/v1/data-spaces/{id}/policies/effective` | 空间 ACTIVE/FROZEN：`space.member` 或 platform.operator；**DISSOLVED：仅 owner 或 platform.operator**（"不再对外提供访问，仅治理/审计可查"） | 无体 → `List<EffectivePolicyItemView>{entryKey, displayName, effectiveValue, source(PLATFORM\|SPACE), provenance(INHERITED\|SPACE_EFFECTIVE\|SPACE_NOT_EFFECTIVE_TAKE_STRICTER), note, platformValue, spaceValue?, spaceStatus?, redline}` | 行为 7 规则 1/3/5 |

通用约定（沿 3.2.3/3.2.4 hifi §1）：路径 `{id}` 为空间技术 id、`{entryId}` 为平台条目技术 id；空间不存在 = `1006C0004`；身份与角色经 `common/auth`（`AuthContext` / 角色头，`X-Ctds-Subject` 演示期身份）；控制器分两新建类沿 3.2.4 E5 资源域分离先例（`PlatformPolicyController` / `SpacePolicyController`）。

## 2. 错误码表（`SpaceErrorCodes` 1006 段顺延新增 3 码；既有 0001~0011 / S0001 语义不变）

| 码 | 常量 | 语义 | HTTP |
| --- | --- | --- | --- |
| 1006C0012 | POLICY_ENTRY_INVALID | 条目键不在目录 / 值不在该键值域 / 请求结构与作用域错配（覆盖请求携带 redline 等）/ 平台级同键重复创建 | 400 |
| 1006C0013 | REDLINE_LOOSENING_REJECTED | 红线条目放宽方向覆盖拒绝（业务文案："该条目为平台级限制项，不可放宽"） | 409 |
| 1006C0014 | POLICY_ENTRY_REQUIRED | 覆盖目标平台条目定位失败（键无 ACTIVE 平台条目——统一文案防探测） | 404 |

**复用既有码（不新增）**：`1006C0002`（空间状态门槛——冻结/解散空间的策略写动作一律拒；~~文案表意"空间当前不可变更策略"~~ **勘误 E3：文案沿 3.2.3 共用门槛常量"空间当前状态不允许该操作"**，语义覆盖策略场景）、`1006C0004`（空间不存在）、`1006C0007`（越权一律拒绝——非成员读视图/无权覆盖/平台面越权）。字段长度/非法枚举走应用服务长度门槛 + common PARAM_INVALID 通道（400，沿 3.2.4 E4 先例）。

## 3. 目录注册表与解析引擎（领域层纯函数，行为 7 规则 2/3/6 的核心）

- **`PolicyCatalog`**（domain，final 类 + 静态注册，**策略模型权威定义点**）：`record EntryDefinition(String key, String displayName, Map<String, Integer> strictness)`（值 → 严格度，**单射**：同键值域内无并列严格度）；静态注册 3 键（lofi Q1-A）：
  | 键 | 显示名 | 值域（严格度） |
  | --- | --- | --- |
  | `data.visibility` | 数据可见范围 | `SPACE_MEMBER`(2) / `ALL_PLATFORM`(1) |
  | `data.retention` | 数据留存期限 | `D30`(4) / `D90`(3) / `D180`(2) / `D365`(1) |
  | `member.data_export` | 成员数据导出 | `FORBIDDEN`(3) / `APPROVAL_REQUIRED`(2) / `ALLOWED`(1) |
  - 对外 API：~~`find(key)`（Optional）/ `requireDefinition(key)`（未注册 → 应用层转 0012）/ `strictness(def, value)`（越域 → 应用层转 0012）/ `isLoosening(def, fromValue, toValue)`~~ **勘误 E1（按实现定稿）**：`find(key)`（Optional）/ `isDefined(key)` / `keys()`（声明序）/ `strictnessOf(def, value)`（**越域由目录直抛 0012 兜底**——防库内数据与目录漂移 NPE，评审②/④跟踪项收口）/ `isLoosening(def, fromValue, toValue)`；"未注册键 → 0012"下沉为 `SpacePolicyService.requireDefinition` 私有方法。**扩目录 = 注册表追加一行登记**（键命名规范：域.名词小写点分）。
- **`EffectivePolicyResolver`**（domain，纯函数无 IO）：输入 = 平台 ACTIVE 条目集 + 空间条目集（ACTIVE/ARCHIVED 均参与——归档即终态值）→ 输出 = `List<EffectivePolicyRow>` 按**目录声明序**稳定排序：
  - 遍历**存在 ACTIVE 平台条目的目录键**（无平台条目的键不出现于视图——诚实缺省，Q6-A 种子保证 3/3 齐备，治理端点维持齐备）；每行取 **max(平台严格度, 空间严格度)** 侧为生效值；
  - 来源三态（provenance，规则 3"可解释"）：`INHERITED`（无空间覆盖——"继承自平台"）/ `SPACE_EFFECTIVE`（空间覆盖更严或平台无该值~~——"空间级生效"~~ **勘误 E2：平级同值的显式空间覆盖亦计入本态**（严格度单射下平级即同值，有效值无差异；note 以"空间级生效（同值显式配置）"区分文案；"平台无该值"分支因 §3 诚实缺省不可达））/ `SPACE_NOT_EFFECTIVE_TAKE_STRICTER`（空间覆盖存在但平台更严——"空间覆盖未生效（取严）"，note 说明"依据 entry_key 严格度：平台 X(严) ≥ 空间 Y(宽)，取平台级"）；
  - 空间行状态（ACTIVE/ARCHIVED）随行透出（`spaceStatus`，归档可查不可变口径）；
  - **解析 = 读时计算不落库**（无物化表；平台值变更即时反映于视图，无同步一致性问题）。

## 4. 写门与事务契约（行为 7 规则 2/4/5；沿 3.2.3/3.2.4 乐观门槛与留痕同事务先例）

- **空间覆盖写门链**（端点 4，`@Transactional` 单事务）：
  1. 空间定位（不存在 → 0004）→ **空间状态门槛**：非 ACTIVE → `1006C0002`（冻结/解散一律拒——规则 5；含归档条目不可变，因解散即非 ACTIVE）；
  2. 目录与值校验：键未注册/值越域/携带非法字段 → `1006C0012`；
  3. 平台条目定位：`WHERE entry_key=? AND scope='PLATFORM' AND status='ACTIVE'`，0 行 → `1006C0014`（统一文案防探测）；
  4. **同值幂等**：该键当前生效值 = 提交值 → 200 返回现状态、**无操作无留痕**（沿 3.2.4 E7 先例）；
  5. **红线放宽判定**（§6.5 硬约束，代码强制）：`platform.redline && strictness(提交值) < strictness(平台值)` → `1006C0013` + **POLICY_OVERRIDE_REJECTED 留痕**（result=DENIED，from=当前生效值 → to=提交值，reason=业务文案"该条目为平台级限制项，不可放宽"）——留痕与拒绝同事务；
  6. **落库 + 留痕**：该空间同键 ACTIVE 覆盖行存在 → `UPDATE entry_value=? WHERE id=? AND space_id=? AND status='ACTIVE'`（0 行=并发归档 → 0002）；不存在 → INSERT（`scope='SPACE'`, space_id, **platform_entry_id=平台条目 id 显式指向**，`is_redline=0`——红线仅平台级，载体契约）；POLICY_OVERRIDE 留痕（from=覆盖前该键**生效值**（取严解析现状）→ to=提交值，operator 四要素）；任一步失败整体回滚；
  7. 响应回显生效结果与来源标注（§3 解析器对该键现算）。
- **平台条目创建/变更**（端点 1/2，`@Transactional`）：创建 = INSERT PLATFORM 行（space_id/platform_entry_id 均 NULL，scope_uniq 生成列记 0；同键已存在 ACTIVE → 应用前置判定 `1006C0012`，`uk_scope_key` DB 兜底）+ POLICY_DEFINE 留痕（from=NULL → to=值）；变更 = 键不可变更（body 仅值/红线）→ `UPDATE ... WHERE id=? AND status='ACTIVE'`（0 行 → 0014 同形）+ POLICY_DEFINE 留痕（from=原值 → to=新值；红线标记变更同记 0/1）；同值无变更 = 幂等无操作无留痕；**平台自己可放宽自己的条目**（红线是约束空间覆盖的，平台是定义者——诚实语义登记）；条目**无删除端点**（治理留痕完整）。
- **动作码**：POLICY_OVERRIDE / POLICY_OVERRIDE_REJECTED 复用 V2 既有预留（零登记）；**POLICY_DEFINE 为新增码，经 V3 迁移在 `space_action_log.action` 注释登记**（沿 V2 先例，不删不改既有码）。
- **留痕 from/to**：一律走 `insertLog` 统一回填契约（3.2.4 移交④口径延续）；值均为目录短枚举（≤20 字符），V2 放宽后的 1024 列宽余量充足。

## 5. 权限面（承接项③复用；行为 7 权限口径沿 3.2.4 定稿延展）

- **新增权限点常量**：`SpacePermissions.PLATFORM_POLICY = "platform.policy"`（平台策略治理：端点 1/2/3）；
- **yml 追加**（space-service `application.yml` 唯一配置改动）：`ctds.auth.permissions.platform.operator: space.admin,space.member` → **追加 `,platform.policy`**；
- **判定实现**：Guard 既有方法**零改动**——平台面经新增薄封装 `hasPlatformPermission(point)` = `hasPermission(null, List.of(), point)`（空间内角色折算空转、仅角色头来源命中；新增方法为纯新增，既有签名与语义不变，3.2.4 T17 矩阵不回归）；
- **空间面门**：覆盖提交 = `canManage`（`space.admin`——owner/ADMIN/角色头折算，沿统一权限面）；有效视图 = `canActAsMember`（`space.member`）或 `isPlatformOperator`；**DISSOLVED 视图门 = `isOwner`（列口径）或 `isPlatformOperator`**（普通成员不含——"解散后不再对外提供访问"；剧本 S3-4 所有者可查 / S3-5 非成员被拒）；
- 判定失败由调用方落 DENIED/ACCESS_DENIED 留痕后抛 `1006C0007`（沿 3.2.4 模式）。

## 6. 读面与留痕口径（行为 7 规则 4/5；沿 3.2.4 §6 模式）

| 场景 | 对外响应 | 对内留痕 |
| --- | --- | --- |
| 非成员请求有效策略视图（ACTIVE/FROZEN 空间） | `1006C0007`（沿 3.2.4 成员列表读面同族口径） | `ACCESS_DENIED`，target=(SPACE, 空间 id)，无数据原文 |
| 非 owner/operator 请求已解散空间策略视图 | `1006C0007`（存在性不额外遮蔽——非成员本不可见） | 同上 |
| 红线放宽覆盖被拒 | `1006C0013`（业务文案） | `POLICY_OVERRIDE_REJECTED`（DENIED，from→to，reason 文案）——**拒绝同样留痕**（规则 4） |
| 写面越权（覆盖/平台面） | `1006C0007` | DENIED 留痕（既有模式） |

## 7. 幂等与移交收敛登记

- **同值幂等**（覆盖/平台变更双向）：重复提交现值 → 200 无操作无留痕（§4 写门第 4 步；沿 3.2.4 E7"同角色重复设定"先例）；`uk_scope_key` 为并发窗口 DB 兜底（DuplicateKeyException → `1006C0012` 文案）；
- **承接项④ 幂等键长候选**：本域业务幂等（同值门槛 + 载体唯一列），无 `@Idempotent` 键——256/257 边缘冲突在本域不发生；common 沉淀候选**保留登记不在本包实现**（沿 3.2.4 §7 口径）；
- **C-4.x 对齐移交（规则 6）**：`PolicyCatalog` = 空间域策略条目语言权威定义；C-4.x 使用控制策略设计时**须引用/对齐本模型**（禁止两处各自定义）——随台账"下一包"行与任务卡 §四 登记；
- **V3 迁移**（Q6-A）：① `space_action_log.action` 注释登记 POLICY_DEFINE（UPDATE 注释，沿 V2 先例）；② 平台基线种子 3 条（`data.visibility=SPACE_MEMBER` 红线 / `data.retention=D90` 红线 / `member.data_export=ALLOWED` 非红线，均 ACTIVE）——平台基线配置非演示残留，清理口径登记"保留"。

## 8. 测试计划（集成 Testcontainers mysql:8.0 实跑 + 目录/解析器纯单测；映射表每格可指到测试行）

| # | 测试 | 断言要点 | 映射 |
| --- | --- | --- | --- |
| T1 | 继承默认 | 无覆盖键 effective=平台值 + provenance=INHERITED（"继承自平台"） | 规则 1；剧本 S2-1；验收标准 1 |
| T2 | **红线放宽拒（反向探针必红）** | visibility 空间提交 ALL_PLATFORM → 0013 + POLICY_OVERRIDE_REJECTED 留痕（from→to+文案）+ effective 保持平台值；**引擎直调放宽判定单测同断言（删除判定即红）** | 规则 2；§6.5 硬约束；剧本 S2-2；验收标准 2 |
| T3 | 红线收紧成 | retention D90→D30 落库生效 + POLICY_OVERRIDE 留痕（from=D90→to=D30）+ provenance=SPACE_EFFECTIVE | 规则 2；剧本 S2-3；验收标准 2 |
| T4 | 非红线覆盖生效 | export→APPROVAL_REQUIRED 生效 + 来源标注"空间级" | 剧本 S2-4 |
| T5 | 冲突取严可解释 | 平台面收紧 export→FORBIDDEN 后空间覆盖 ALLOWED：落库成功但 effective=平台值 + provenance=SPACE_NOT_EFFECTIVE_TAKE_STRICTER + note 说明依据 | 规则 3；剧本 S2-5；验收标准 3 |
| T6 | 目录与值域校验 | 非法键/越域值/覆盖请求携带 redline/平台同键重复创建 → 全部 `1006C0012` | 规则 6；Q5 |
| T7 | 条目定位防探测 | 覆盖提交无平台条目的键 → `1006C0014` 统一文案 | Q5 |
| T8 | 同值幂等 | 重复覆盖现值 → 200、留痕数不变、覆盖行数不变 | §7；缺口声明 2 |
| T9 | 冻结联动 | 冻结空间覆盖 → `1006C0002`；冻结期视图保留原覆盖值；恢复后覆盖仍生效 | 规则 5；剧本 S3-2/S3-3 |
| T10 | 解散归档不可变 | 3.2.3 解散后（条目已 ARCHIVED）：覆盖 → `1006C0002`；owner/平台 operator 可查视图（含归档值与 spaceStatus）；普通成员视图被拒 | 规则 5；剧本 S3-4/S3-6；验收标准 4 |
| T11 | 平台面治理 | operator 创建/变更条目 + POLICY_DEFINE 留痕（from→to）；空间 admin 调平台面 → `1006C0007` | 规则 1/4；Q4；剧本 S1-5 治理面 |
| T12 | 权限矩阵 | 覆盖：owner/admin ✓、member ✗（0007+DENIED 留痕）；视图：member ✓、非成员 ✗（0007+ACCESS_DENIED） | 承接项③；行为 7 权限口径 |
| T13 | 载体契约探针 | 同空间同键两条 ACTIVE 覆盖行直插必撞 `uk_scope_key`（DB 兜底实证）；覆盖行 platform_entry_id 显式指向断言 | 承接项①；3.2.2 §1.5 |
| T14 | 错误码格式锚 | `SpaceErrorCodes` **15 码**全量：9 位/段位 1006/类型位/序号顺延（含新增 0012~0014） | 值域必填格（-2138 教训） |
| T15 | 目录封闭性纯单测 | 注册表键集合=3、每键值域封闭、严格度单射、越域查询异常、isLoosening 方向判定 | 规则 6；§3 |
| T16 | 解析器纯单测 | 取严方向/三态 provenance/目录声明序稳定排序/归档值参与解析 | 规则 3；§3 |
| T17 | V3 迁移与种子 | 迁移后平台条目恰 3 条 ACTIVE（红线标记 1/1/0）；`space_action_log.action` 注释含 POLICY_DEFINE | Q6；§7 |
| T18 | 平台端点 2 契约门（评审修复批补充） | 至少一项变更（entryValue/redline 均缺省）→ 1000C0001；同值幂等（§7 双向的平台侧）→ 200 无操作无留痕；红线标记翻转 0→1 落库 + 留痕 reasonNote 同记 0/1（§4） | §1 端点 2；§4；§7 |
| T19 | 平台条目列表（评审修复批补充） | 端点 3 治理面只读：基线 3 条分页视图（total/totalPages/字段）+ 无 platform.policy 来源主体 → 0007 | §1 端点 3；剧本 S1-5 |

**本地门禁**：`mvn -B -ntp -pl services/space-service -am test` + `checkstyle:check`；subject 等其他模块零改动不重跑；前端不触碰。

## 9. 边界值与异常行为

- entryKey ≤64 / entryValue ≤1024（列宽；目录值实际均 ≤20 短枚举）/ 覆盖请求**不含** redline 字段（结构错配 → 0012，红线标记仅平台面可写）；非法 JSON 与缺字段 → common PARAM_INVALID（400）；**勘误 E4：长度门槛由封闭值域校验前置兜住**（键/值须先命中目录封闭集合，越域值先于长度被 0012 拒——独立长度门槛在本域不可达，未单独实现；通用请求体大小上限登记 DB-27）；
- 平台列表分页（common-pagination，沿 3.2.3 端点口径）；有效视图不分页（目录键封闭 ≤3 行，整表返回）；
- 空间不存在与不可访问口径（§5/§6 表）；平台条目 id 定位失败 → 0014 同形（变更端点）；
- 已归档条目参与解析但不可变更（§3/§4）；无平台条目的目录键不出现在视图（诚实缺省）；
- 冻结期间已配置覆盖保留且视图可读、恢复后仍生效（剧本 S3-3 口径，T9 固化）。

## 10. 交付物核对清单

1. 四层代码：interfaces（新建 `PlatformPolicyController` + `SpacePolicyController` + DTO）、application（新增 `SpacePolicyService`；写门链与权限判定）、domain（**`PolicyCatalog` + `EffectivePolicyResolver`** 纯函数引擎；`SpacePermissions.PLATFORM_POLICY`；`SpaceRepository` 追加策略读写与留痕方法，javadoc 含回填契约）、infrastructure（`SpaceJdbcRepository` 对应实现）；
2. `SpaceErrorCodes` 新增 3 码（1006C0012~0014）+ ExceptionHandler 映射（409/404/400）+ 码表↔处理器一致性锚扩展；
3. space-service `application.yml` `platform.operator` 映射追加 `,platform.policy` 1 处；
4. **V3 迁移**（动作码注释登记 + 平台基线种子 3 条；无新表无列变更）；
5. 测试 T1~T17 全绿 + checkstyle 0 违规（含 T15/T16 纯单测与 T2 引擎直调必红反向探针）；
6. 本卡与 lofi/hifi 签署回填；台账与日志；C-2.3 剧本兼容性核验声明（预期"无需更新"，界面占位不变）；C-4.x 对齐移交登记。

## 11. 勘误记录（4 视角评审后回填，2026-09-27 评审会话）

> 依据评审卡①"偏离设计项先改设计重新确认"与 3.2.3"勘误设计而非改代码"先例：以下为**设计文字与实现实况的措辞级勘误**（有效行为语义零变更，不做编码回退），随修复批同批落盘。

| 编号 | 勘误点 | 原文位置 | 内容 | 来源 |
| --- | --- | --- | --- | --- |
| E1 | 目录 API 形态按实现定稿 | §3 | `requireDefinition` 下沉为 `SpacePolicyService` 私有方法；`strictness` 定名 `strictnessOf`；越域 → 0012 由目录兜底直抛（评审②/④跟踪项收口） | 评审① P2 / ② P2 / ④ P2 |
| E2 | SPACE_EFFECTIVE 平级同值补位 | §3 | 平级同值显式空间覆盖计入"空间级生效"态，note 区分文案；"平台无该值"分支因诚实缺省不可达 | 评审① P2 |
| E3 | 0002 文案共用常量 | §2 | 复用 0002 沿 3.2.3 共用门槛文案"空间当前状态不允许该操作"（语义覆盖策略场景），T9/T10 已补 message 断言——见修复批 | 评审④ P2 |
| E4 | 长度门槛由封闭值域前置兜住 | §9 | 键/值须先命中目录封闭集合，独立长度门槛在本域不可达；通用请求体大小上限登记 DB-27 | 评审② P2 |
