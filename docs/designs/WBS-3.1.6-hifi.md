# WBS-3.1.6 C-1.1 测试补全与集成 · 高保真设计

| 字段 | 内容 |
| --- | --- |
| 任务卡 | WBS-3.1.6（产出定义：单元/集成测试达标、联调通过；前置 3.1.3~3.1.5） |
| 编码契约 | 本表定稿并经 PO 确认后，任何与本表不一致的实现 = 打回项（章程 2.6） |
| 关联 | 规格 C-1.1 V1.1；四包 hifi（WBS-3.1.2~3.1.5）；验收剧本 V1.1；ADR-005/006/007/008/010/011/016；ADR-001 质量工具链（JaCoCo 已冻结在册）；日志 26-09-14-1005 交接节 |
| 设计裁决 | Q1 覆盖率口径＝章程字面（核心 ≥80%，非核心链路 ≥70%；crypto/auth 存量缺口移交 2.2.4 不混入本包）；Q2 联调＝三幕剧本一次性真实浏览器走查留痕＋单测/集成全绿兜底，剧本 E2E 资产固化登记观察项随 3.9.x/CI 统一裁决；Q3 requireSubject 上收＝做；Q4 演示数据保留至验收——**PO 已确认（2026-09-14，四问均采建议，见文末确认记录）** |

## 1. 覆盖率测量口径（本包只测量，不开门禁）

- 工具：JaCoCo 0.8.13 **命令行临时注入**测量（`mvn org.jacoco:...:prepare-agent test ...:report`），**不改任何 pom、不改 gates-config**——阈值落地属工作包 2.2.4（其登记表 `coverage: PENDING-JACOCO` 维持不动），本包交付说明附"接入建议与实测数字"供 2.2.4 承接；
- 达标线（Q1 裁决口径）：subject-service、std-adapter 行覆盖 ≥70%；common-crypto、common-auth（核心模块）≥80%，未达标者登记移交；
- 执行环境：本机 Docker 在线（Testcontainers 真实 MySQL 8.0 执行，无 Docker 即集成用例跳过的"假达标"按 ADR-010 第 4 条拒绝采信——本包达标数字必须在有 Docker 环境产出并在交付说明注明）。

基线实测（2026-09-14 回填；命令 = §1 口径，Docker 在线、subject 96 用例含真实 MySQL 容器全部执行，BUILD SUCCESS 3:38）：

| 模块 | 行覆盖 | 分支覆盖 | 达标线（行） | 判定 |
| --- | --- | --- | --- | --- |
| subject-service | **97.4%** | 82.6% | ≥70% | **达标（余量显著）** |
| std-adapter | **93.7%** | 70.2% | ≥70% | **达标** |
| common-crypto（核心） | **93.0%** | 76.9% | ≥80% | **行达标** |
| common-auth（核心） | **91.3%** | 77.8% | ≥80% | **行达标** |
| common-idempotency | 90.7% | 79.2% | ≥70% | 达标 |
| common-errorcode / logging / pagination | 82.6% / 93.7% / 99.0% | 93.8% / 82.7% / 92.3% | ≥70% | 达标 |

**基线结论**：行覆盖口径下 C-1.1 链路与两个核心 common 模块**全部达标，无需为凑数字补测**——本包补测（§2/§3）全部由"行为映射缺口 + 登记欠账"驱动，不以拉高数字为目的。分支覆盖核心模块现值 76.9%~79.2%（crypto/auth）：**分支是否入门禁、阈值多少属 2.2.4"阈值配置"职权**，本包仅登记实测值供其裁决，不据此补测。补测完成后按 §6 复测回填终值。

终值复测（2026-09-14，本包补测+修复全部落定后；测量方式=目标模块 `-pl`（依赖先 `install -DskipTests`），subject 111/111 全绿）：

| 模块 | 行覆盖 | 分支覆盖 | 基线 → 终值 |
| --- | --- | --- | --- |
| subject-service | **98.0%** | 83.0% | 97.4 → 98.0（+T1~T8/S1） |
| std-adapter | 93.7% | 70.2% | 不变（本包无该模块改动） |
| common-crypto | 93.0% | 76.9% | 不变（核心 ≥80% 行达标） |
| common-auth | 91.3% | 77.8% | 不变（核心 ≥80% 行达标） |

**登记观察项（2.4.7 存量测试缺陷，非本包改动）**：`IdempotencyAdviceTest.返回null视为合法结果且重复返回null` 在"jacoco 插桩 + 多容器并发负载"下两度红（PROCESSING_TTL/RESULT_EXPIRE_SECONDS=1s，首调用被拖过 1s 窗口 → 第二次调用重新执行）；无插桩标准门禁 `mvn test` 稳定绿（当日多轮实证）。建议处置：该用例 TTL 放宽至 ≥5s 或注入可控时钟（随 2.2.4 门禁阈值包或幂等组件后续包，走缺陷修复免设计口径）。本包覆盖率测量因此改用"目标模块单独测"方式，数字不受影响。

## 2. 后端补测行为清单（T1~T8，逐条含红锚验证方式）

> 测试先行：先写用例确认"对当前实现即绿或如实红"；红锚 = 按"删锚验证"列临时破坏实现→测试必红→还原。全部落既有集成/单测类，不新建平行世界。

| # | 缺口来源 | 新增测试（类#方法） | 断言要点 | 删锚验证（必红项） |
| --- | --- | --- | --- | --- |
| T1 | 规格行为 3-3"次日自动恢复"（剧本 S2 步骤 4 依赖口径；现状仅单测时钟桩） | `CertificationIntegrationTest#yesterdayFailuresDoNotBlockTodaySixthAttemptDbLevel` | 直插 5 条**昨日** FAIL(counted=1) 核验记录 → 今日发起核验**不被当日限额拦截**正常走渠道；再直插 5 条**今日** FAIL → 第 6 次 1004B0005 拒绝（对照锚） | 把 `countFailuresSince` 的 `created_at >= ?` 条件删成全量计数 → 红 |
| T2 | 3.1.5 hifi E2（上包登记欠账：理由超长仅单测层） | `ReviewIntegrationTest#rejectOverlongReasonRejectedAtHttpLayer` | 201 字符理由 POST → HTTP 200 信封内 400 参数错误码、主体直查库仍 PENDING_REVIEW、无 DENIED 之外的审核流转留痕 | 去掉服务端长度校验 → 红 |
| T3 | 3.1.5 hifi 接口契约"清单按申请时间升序"（有契约无断言） | `ReviewIntegrationTest#queueOrderedByApplicationTimeAsc` | 造 3 个待审核主体后直改 `created_at` 打乱物理序 → 清单返回顺序严格按申请时间升序；分页跨界仍保序 | 仓储 ORDER BY 列改错（如按 id） → 红 |
| T4 | 3.1.2 hifi B7 计划测试"状态枚举封闭"未落 | 新 `domain/SubjectStatusTest` | `values()` 恰为 5 态且名称集合固定（PENDING_CERT/PENDING_REVIEW/ADMITTED/CERT_FAILED/REJECTED）；中文展示名映射逐条断言；`valueOf("未知")` 抛异常——规格行为 4"不得私自增删状态"的机器锁 | 枚举加/改名 → 红 |
| T5a | 3.1.3 hifi 边界表"认证端并发重复点击" | `CertificationIntegrationTest#concurrentVerifyExactlyOneTransition` | 已确认主体 20 线程并发发起"核验通过"→ 仅 1 次成功流转 PENDING_REVIEW，其余被状态门槛拒（1004 族）；状态流转记录恰 1 条 | 移除仓储乐观状态更新 → 红 |
| T5b | 3.1.4 hifi 边界表"政务重复提交并发"（承诺"3.1.5 前补"顺延项） | `GovCaCertificationIntegrationTest#concurrentGovSubmissionExactlyOneWins` | 同政务主体 2 线程并发提交证书验证 → 恰 1 成功进 PENDING_REVIEW，1 被门槛拒；核验流水恰 1 条 | 同上 |
| T6 | 3.1.3/3.1.4 hifi 配置"容器 6MB/业务 5MB、证书 2MB"HTTP 封套（现状仅单测层） | `CertificationIntegrationTest#oversizedMultipartGetsUnifiedEnvelope`；`GovCaCertificationIntegrationTest#oversizedCertFileRejectedAtHttpLayer` | 超容器上限 multipart POST → 统一错误信封（不泄露堆栈、错误码在主体模块位）；2MB+1 证书文件 → 1004 族拒绝文案、材料零落库 | 删业务大小校验 → 红（业务层那条） |
| T7 | 行为 7-1 收口纪律"业务只见接口"（架构守卫缺口） | `LayerRulesTest#subjectMustNotDependOnMockChannel` | ArchUnit 规则：`com.ctds.subject..`（main）不得依赖 `com.ctds.std.certification.mock..`（模拟渠道实现类） | 在 subject 里 import 一个 mock 类 → 红 |
| T8 | 行为 7-4"渠道异常不计失败次数"的库级组合（现状两侧分开锚定） | `CertificationIntegrationTest#channelErrorRowsNotCountedAgainstDailyFailLimit` | 直插 1 条 CHANNEL_ERROR(counted=0) + 5 条 FAIL(counted=1) → 第 6 次被拒；删掉 CHANNEL_ERROR 行中 1 条 FAIL → 第 6 次放行（组合证明 counted 过滤生效） | 仓储查询去掉 `counted = 1` → 红 |

## 3. 前端补测行为清单（F1~F4，Vitest，随门禁 frontendTest）

| # | 缺口来源 | 新增用例 | 断言要点 | 删锚验证 |
| --- | --- | --- | --- | --- |
| F1 | 行为 1-1 前端必填校验（现 RegisterView.spec 无提交拦截用例） | `views/subject/RegisterView.spec.ts` +2 例 | 必填缺失点击提交 → 表单错误逐项显示、**不发**注册请求；填齐后提交 → 调 `registerSubject` 一次并展示申请编号 | 删 el-form rules 或提交前 validate → 红 |
| F2 | 行为 2-1"OCR 回填"值绑定（现仅断言分区出现）；行为 3 核验交互 | `views/subject/CertificationView.spec.ts` +3 例 | 上传后 mock OCR 四要素**值**出现在核对表单对应输入框；发起核验展示"通过/不通过"结论与剩余次数；"次数已用完"文案渲染 | 模板改回占位常量/删结论区 → 红 |
| F3 | 3.1.5 hifi 交互要点 4"状态已变化提示"（DetailView 缺失） | `views/review/DetailView.spec.ts` +1 例 | 操作端点返回业务错误码（模拟状态已变）→ 页面显示后端 message 并刷新档案 | 删错误提示分支 → 红 |
| F4 | 越权直连 `/review/queue`（现仅测菜单显隐与 /admin-only） | `router/router.spec.ts` +1 例 | 无 review 权限角色 beforeEach 直导航 → 重定向（不渲染清单页） | 删路由守卫权限判断 → 红 |

## 4. 结构整备（S1，Q3 裁决口径）

- `ReviewService.requireSubject` 与 `CertificationService.requireSubject` 同型上收至 `SubjectOpsSupport`（注入 `SubjectRepository`，方法签名含"格式校验+查库或 1000C0003"两段，两服务删除私有重复）；
- **行为零变化约束**：不改任何错误码/文案/审计语义；后端 96 测试 + 前端 58 测试全绿即证明无回归；红锚：故意让上收后的 requireSubject 不校验格式 → `invalidSubjectNo` 类既有用例必红。

## 5. 联调方案（Q2 裁决口径：三幕剧本一次性真实浏览器走查）

环境编排（沿 3.1.5 验收先例）：① sc-mysql（Docker）保留，subject-service mysql profile 起 8080（`CTDS_SM4_KEY_FILE` 指向既有 subject-demo.keys）；② `npm run dev` 起 5173；③ Playwright 临时脚本按剧本 S1（10 步）/S2（7 步，步骤 4 次日恢复以"直插昨日 FAIL 记录"等效替代人工改日期——口径在走查记录注明）/S3（4 步）逐步执行、逐步截图留痕；④ 走查结果写入开发日志"联调记录"节；临时脚本用毕即删（沿 3.1.5 先例），**剧本附录界面核对修订**（剧本维护说明第 3 条：3.1.2~3.1.5 交付后按钮文案核对）随走查一并完成并留痕。
- 登记观察项（移交 3.9.x/CI 统一裁决，不在本包做）：剧本 S1/S3 固化为自动回归资产的编排前提（后端+DB 供给、真实认证登录）。

## 6. 交付门禁与依赖声明

- 门禁：`mvn -B -ntp test`（subject-service + std-adapter + common 全链，Docker 在线真实执行）、`checkstyle:check` 0 违规、前端 `npm run lint` / `npm run test` 全绿、run-gates 全量 PASS；覆盖率按 §1 命令复测达标回填 §1 表；
- 依赖清单：**零新增**（jacoco 仅镜像上既有版本的 CLI 调用；前端无新 npm 包；dependencies.md 零变更）；
- 规格外实现声明：无（S1 为登记观察项处置，T7 为已冻结 ADR-008 收口纪律的守卫固化，均不新增业务行为）；
- Flyway：零新迁移（T1/T3 直插数据仅存在于一次性容器内，不入库脚本）。

## 7. 映射表（规格验收标准 ↔ 补测项）

| 规格条目 | 补测项 | 剧本步骤 |
| --- | --- | --- |
| 行为 3 验收-3 / 第 3 条（重试上限与次日恢复） | T1、T8 | S2 步骤 3/4 |
| 行为 5 验收-2（理由必填含超长） | T2 | S2 步骤 5（必填已有；超长走查注记） |
| 行为 5 第 1 条（清单） | T3 | S3 步骤 4 前的清单可见性 |
| 行为 4 第 1 条（状态机封闭） | T4 | —（机器锁，无界面步骤） |
| 行为 1-3 / 行为 6（并发防重） | T5a/T5b | S1 步骤 2（走查含双提交） |
| 行为 2 第 1 条 / 行为 6 第 1 条（上传限制） | T6 | 走查附注（界面已有大小限制提示） |
| 行为 7 第 1/4 条 | T7、T8 | — |
| 前端交互（F1~F4） | — | 走查同步覆盖 |

## 8. 实现期补记（编码中如实留痕，非改设计裁决）

1. **T7 规则范围收窄**：首版按"subject 全模块禁依赖 MockCertificationChannel"落地即红——唯一命中是 `infrastructure/CertificationBeanConfig`（ADR-008 §4 明示的 Bean 注册替换点，属合法装配）。规则收窄为 domain/application/interfaces 业务三层禁依赖。**该首版红灯本身即 T7 红锚实证**（规则对真实依赖引用立即生效）。
2. **vitest 环境缺陷修复（前端主链路外唯一的配置面变更）**：element-plus 在 vitest SSR 下被外置，其 `import AsyncValidator from 'async-validator'` 走 Node CJS interop 拿到整个 module.exports 对象，`new` 抛 TypeError 且被 EP `catch (fields)` 吞成"校验通过"——**既有 58 例中凡依赖 el-form rules 的断言实际从未生效**（本包 F1 首验时暴露：jsdom 下必填提示不渲染）。修复 = vite.config.ts `test.server.deps.inline: [/element-plus/, /async-validator/]`（走 dist-web ESM，与浏览器构建一致）。业务代码零改动。教训入记忆：测试环境组件行为断言必须先以"故意不填必填"式反向探针验证测试本身能红。
3. **RegisterView.submit 加 validate 拒绝捕获**（随 F1 交付的最小前端代码修正）：原 `await formRef.value.validate()` 失败即抛 unhandled rejection（控制台报错、无功能影响）；现捕获后静默 return（字段级红字由 el-form 自行展示）。行为口径不变、消除噪声、使 F1 红锚可稳定断言。hifi §3 F1 行"删 rules 或 validate 即红"验证方式在本修复后为真。

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 确认人（PO/编排师） | 结论 | 时间 |
| --- | --- | --- |
| 编排师 | **确认进入编码**——AskUserQuestion 即时选定，四问均采建议（Q1 章程字面口径 / Q2 一次性走查留痕+固化登记观察项 / Q3 requireSubject 上收本包做 / Q4 演示数据保留至验收） | 2026-09-14 10:4x |
