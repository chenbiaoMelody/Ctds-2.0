package com.ctds.contract.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.UsageControlPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 解析器单测矩阵（WBS-3.4.4 hifi §6）：合法矩阵（五要素全启用〔附录 B 预置值同构〕/ 部分启用 /
 * 显式无限制 / 无策略节点 / 带版本）× 非法矩阵（R2 未知字段名与结构 / R3 未知版本 / R4 次数取值 /
 * R5 期限时点 / R6 文本空白与 trim / R7 互斥）+ satisfiesConfirmGate 两臂 + 容忍读语义 +
 * 解析→序列化→容忍读回环。四类新规则（R3/R6-trim/R7/R5）测试先行先红后绿。
 */
class UsagePolicyDslParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** R5 时点基准（固定注入——不取系统时钟，消除跨午夜脆性；期限起始 = 提交日为合法边界）。 */
    private static final LocalDate SUBMISSION = LocalDate.of(2026, 10, 6);

    private static UsagePolicyDslParser.ParseResult parse(final String json) throws Exception {
        return UsagePolicyDslParser.parse(MAPPER.readTree(json), SUBMISSION);
    }

    // ==== 合法矩阵 ====

    @Test
    void legalAllFiveElementsEnabledWithVersion() throws Exception {
        final UsagePolicyDslParser.ParseResult result = parse("{\"dslVersion\":\"1.0\","
                + "\"quota\":{\"enabled\":true,\"maxCount\":100},"
                + "\"term\":{\"enabled\":true,\"startDate\":\"2026-10-07\",\"endDate\":\"2026-11-05\"},"
                + "\"purpose\":{\"enabled\":true,\"text\":\"风控建模\"},"
                + "\"territory\":{\"enabled\":true,\"text\":\"本市域\"},"
                + "\"noRedistribution\":{\"enabled\":true},\"noRestrictionDeclared\":false}");
        assertThat(result.violations()).isEmpty();
        assertThat(result.isValid()).isTrue();
        final UsageControlPolicy policy = result.policy();
        assertThat(policy.quota().enabled()).isTrue();
        assertThat(policy.quota().maxCount()).isEqualTo(100);
        assertThat(policy.term().enabled()).isTrue();
        assertThat(policy.term().startDate()).isEqualTo("2026-10-07");
        assertThat(policy.term().endDate()).isEqualTo("2026-11-05");
        assertThat(policy.purpose().text()).isEqualTo("风控建模");
        assertThat(policy.territory().text()).isEqualTo("本市域");
        assertThat(policy.noRedistribution().enabled()).isTrue();
        assertThat(policy.noRestrictionDeclared()).isFalse();
    }

    @Test
    void legalPartialElementsWithoutVersion() throws Exception {
        // 缺省版本容忍 = 1.0（3.4.3 存量载荷与测试夹具零破坏——兼容矩阵）
        final UsagePolicyDslParser.ParseResult result = parse(
                "{\"quota\":{\"enabled\":true,\"maxCount\":3}}");
        assertThat(result.violations()).isEmpty();
        final UsageControlPolicy policy = result.policy();
        assertThat(policy.quota().enabled()).isTrue();
        assertThat(policy.quota().maxCount()).isEqualTo(3);
        assertThat(policy.term().enabled()).isFalse();
        assertThat(policy.purpose().enabled()).isFalse();
        assertThat(policy.territory().enabled()).isFalse();
        assertThat(policy.noRedistribution().enabled()).isFalse();
        assertThat(policy.noRestrictionDeclared()).isFalse();
    }

    @Test
    void legalExplicitNoRestrictionWithAllElementsDisabled() throws Exception {
        final UsagePolicyDslParser.ParseResult result = parse(
                "{\"quota\":{\"enabled\":false},\"noRestrictionDeclared\":true}");
        assertThat(result.violations()).isEmpty();
        assertThat(result.policy().noRestrictionDeclared()).isTrue();
        assertThat(result.policy().quota().enabled()).isFalse();
    }

    @Test
    void legalMissingStrategyNodeFallsToEmptyPolicy() throws Exception {
        assertThat(UsagePolicyDslParser.parse(null, SUBMISSION).violations()).isEmpty();
        assertThat(UsagePolicyDslParser.parse(null, SUBMISSION).policy())
                .isEqualTo(UsageControlPolicy.empty());
        assertThat(UsagePolicyDslParser.parse(MAPPER.readTree("null"), SUBMISSION).policy())
                .isEqualTo(UsageControlPolicy.empty());
    }

    // ==== 非法矩阵：R3 版本门槛（新，fail-closed）====

    @Test
    void r3UnknownVersionRejected() throws Exception {
        final UsagePolicyDslParser.ParseResult result = parse("{\"dslVersion\":\"2.0\","
                + "\"quota\":{\"enabled\":true,\"maxCount\":3}}");
        assertThat(result.violations()).anyMatch(v -> v.contains("dslVersion"));
        assertThat(result.policy()).isEqualTo(UsageControlPolicy.empty());
        // 版本位非字符串同样拒绝
        assertThat(parse("{\"dslVersion\":1.0,\"noRestrictionDeclared\":true}")
                .violations()).isNotEmpty();
    }

    // ==== 非法矩阵：R2 未知要素字段名（新，目录封闭集）与结构（3.4.3 迁入）====

    @Test
    void r2UnknownElementFieldRejectedByCatalogClosedSet() throws Exception {
        final UsagePolicyDslParser.ParseResult result = parse(
                "{\"foo\":{\"enabled\":true},\"noRestrictionDeclared\":false}");
        assertThat(result.violations()).anyMatch(v -> v.contains("未知策略要素") && v.contains("foo"));
    }

    @Test
    void r2ShapeViolationsMigratedFromLegacyChecks() throws Exception {
        assertThat(parse("[1,2]").violations()).anyMatch(v -> v.contains("strategy 须为对象"));
        assertThat(parse("\"策略\"").violations()).anyMatch(v -> v.contains("strategy 须为对象"));
        assertThat(parse("{\"noRestrictionDeclared\":\"yes\"}").violations())
                .anyMatch(v -> v.contains("noRestrictionDeclared 须为布尔"));
        assertThat(parse("{\"quota\":\"x\"}").violations())
                .anyMatch(v -> v.contains("quota 须为含 enabled 布尔的对象"));
        assertThat(parse("{\"quota\":{\"maxCount\":3}}").violations())
                .anyMatch(v -> v.contains("quota 须为含 enabled 布尔的对象"));
        assertThat(parse("{\"term\":{\"enabled\":true,\"startDate\":20261007,"
                + "\"endDate\":\"2026-11-05\"}}").violations())
                .anyMatch(v -> v.contains("term 起止须为字符串日期"));
        assertThat(parse("{\"purpose\":{\"enabled\":true,\"text\":123}}").violations())
                .anyMatch(v -> v.contains("purpose.text 须为字符串"));
    }

    // ==== 非法矩阵：R4 次数取值（3.4.3 迁入）====

    @Test
    void r4QuotaValueMatrix() throws Exception {
        assertThat(parse("{\"quota\":{\"enabled\":true,\"maxCount\":0}}").violations()).isNotEmpty();
        assertThat(parse("{\"quota\":{\"enabled\":true,\"maxCount\":-5}}").violations()).isNotEmpty();
        assertThat(parse("{\"quota\":{\"enabled\":true,\"maxCount\":\"x\"}}").violations())
                .isNotEmpty();
        assertThat(parse("{\"quota\":{\"enabled\":true}}").violations()).isNotEmpty();
        assertThat(parse("{\"quota\":{\"enabled\":true,\"maxCount\":1}}").violations()).isEmpty();
        // 未启用不校验取值
        assertThat(parse("{\"quota\":{\"enabled\":false,\"maxCount\":-5}}").violations()).isEmpty();
    }

    // ==== 非法矩阵：R5 期限取值与时点（时点为新，Q4-④）====

    @Test
    void r5TermMatrix() throws Exception {
        // 格式非法（3.4.3 迁入）
        assertThat(parse("{\"term\":{\"enabled\":true,\"startDate\":\"2026/10/07\","
                + "\"endDate\":\"2026-11-05\"}}").violations()).isNotEmpty();
        // 起止倒置（3.4.3 迁入）
        assertThat(parse("{\"term\":{\"enabled\":true,\"startDate\":\"2027-01-01\","
                + "\"endDate\":\"2026-01-01\"}}").violations()).isNotEmpty();
        // 起始早于提交日（新——规格"期限早于生效日"在提交时点的可判定化）
        assertThat(parse("{\"term\":{\"enabled\":true,\"startDate\":\"2026-10-05\","
                + "\"endDate\":\"2026-11-05\"}}").violations())
                .anyMatch(v -> v.contains("提交日"));
        // 边界：起始 = 提交日 → 合法（≥ 提交日）
        assertThat(parse("{\"term\":{\"enabled\":true,\"startDate\":\"2026-10-06\","
                + "\"endDate\":\"2026-11-05\"}}").violations()).isEmpty();
    }

    // ==== 非法矩阵：R6 文本取值与 trim 规范化（trim 为新，Q4-③）====

    @Test
    void r6TextTrimNormalizedIntoModel() throws Exception {
        // 纯空白 → 拒绝（3.4.3 迁入口径）
        assertThat(parse("{\"purpose\":{\"enabled\":true,\"text\":\"  \"}}").violations())
                .anyMatch(v -> v.contains("用途文本不能为空"));
        assertThat(parse("{\"territory\":{\"enabled\":true,\"text\":\"\\t\"}}").violations())
                .anyMatch(v -> v.contains("域内文本不能为空"));
        // trim 后落模（新——存储与判定用 trim 后值，防 3.4.5 漏拦）
        final UsagePolicyDslParser.ParseResult result = parse(
                "{\"purpose\":{\"enabled\":true,\"text\":\"  风控建模  \"},"
                        + "\"territory\":{\"enabled\":true,\"text\":\" 本市域 \"}}");
        assertThat(result.violations()).isEmpty();
        assertThat(result.policy().purpose().text()).isEqualTo("风控建模");
        assertThat(result.policy().territory().text()).isEqualTo("本市域");
    }

    // ==== 非法矩阵：R7 互斥（新，Q4-②）====

    @Test
    void r7MutexContradictionRejected() throws Exception {
        // "无使用限制"声明与任一要素启用并存 = 语义矛盾
        assertThat(parse("{\"quota\":{\"enabled\":true,\"maxCount\":3},"
                + "\"noRestrictionDeclared\":true}").violations())
                .anyMatch(v -> v.contains("互斥"));
        assertThat(parse("{\"noRedistribution\":{\"enabled\":true},"
                + "\"noRestrictionDeclared\":true}").violations())
                .anyMatch(v -> v.contains("互斥"));
        // 声明 + 五要素全禁用 = 合法（合法矩阵已覆盖合法臂，此处防误杀回归）
        assertThat(parse("{\"quota\":{\"enabled\":false},\"noRestrictionDeclared\":true}")
                .violations()).isEmpty();
    }

    // ==== R8 确认门槛两臂（3.4.3 hasAnyRestrictionOrDeclared 迁入）====

    @Test
    void satisfiesConfirmGateBothArms() {
        assertThat(UsagePolicyDslParser.satisfiesConfirmGate(UsageControlPolicy.empty()))
                .isFalse();
        // 显式声明臂
        assertThat(UsagePolicyDslParser.satisfiesConfirmGate(
                new UsageControlPolicy(UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false), true))).isTrue();
        // 要素启用臂（次数 / 纯开关各一代表）
        assertThat(UsagePolicyDslParser.satisfiesConfirmGate(
                new UsageControlPolicy(UsageControlPolicy.Element.ofCount(true, 100),
                        UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false), false))).isTrue();
        assertThat(UsagePolicyDslParser.satisfiesConfirmGate(
                new UsageControlPolicy(UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(true), false))).isTrue();
    }

    // ==== 读路径容忍解析（密文回读 / QC1 / 视图）====

    @Test
    void parseTolerantDefaultsMissingFieldsAndVersion() throws Exception {
        assertThat(UsagePolicyDslParser.parseTolerant(null))
                .isEqualTo(UsageControlPolicy.empty());
        // 3.4.3 存量形态（无版本行、缺字段）→ 容忍读 = 禁用/空值（零迁移、零 DDL）
        final UsageControlPolicy policy = UsagePolicyDslParser.parseTolerant(
                MAPPER.readTree("{\"quota\":{\"enabled\":true,\"maxCount\":3}}"));
        assertThat(policy.quota().enabled()).isTrue();
        assertThat(policy.quota().maxCount()).isEqualTo(3);
        assertThat(policy.term().enabled()).isFalse();
        assertThat(policy.noRestrictionDeclared()).isFalse();
    }

    @Test
    void parseTolerantFailClosedOnUnknownVersion() throws Exception {
        // 未知版本拒绝解析（不冒充空策略——沿"不冒充"口径，兼容矩阵 §5）
        assertThatThrownBy(() -> UsagePolicyDslParser.parseTolerant(
                MAPPER.readTree("{\"dslVersion\":\"2.0\",\"quota\":{\"enabled\":true}}")))
                .isInstanceOf(ContractBizException.class)
                .extracting(e -> ((ContractBizException) e).getErrorCode().value())
                .isEqualTo(ContractErrorCodes.POLICY_CLAUSE_INVALID.value());
    }

    @Test
    void parseTolerantDoesNotReplaySubmissionTimeChecks() throws Exception {
        // 存量数据不回溯提交时点校验（R5/R7 仅提交路径）：过期期限 / 互斥形态的存量回读不拒绝
        final UsageControlPolicy pastTerm = UsagePolicyDslParser.parseTolerant(
                MAPPER.readTree("{\"term\":{\"enabled\":true,\"startDate\":\"2020-01-01\","
                        + "\"endDate\":\"2020-12-31\"}}"));
        assertThat(pastTerm.term().enabled()).isTrue();
        final UsageControlPolicy legacyMutex = UsagePolicyDslParser.parseTolerant(
                MAPPER.readTree("{\"quota\":{\"enabled\":true,\"maxCount\":3},"
                        + "\"noRestrictionDeclared\":true}"));
        assertThat(legacyMutex.quota().enabled()).isTrue();
        assertThat(legacyMutex.noRestrictionDeclared()).isTrue();
    }

    @Test
    void parseAndTolerantReadRoundTrip() throws Exception {
        // 提交路径规范化模型 → 固定字段序序列化 → 容忍读回环一致（存储与判定同源）
        final UsagePolicyDslParser.ParseResult result = parse(
                "{\"quota\":{\"enabled\":true,\"maxCount\":100},"
                        + "\"term\":{\"enabled\":true,\"startDate\":\"2026-10-07\","
                        + "\"endDate\":\"2026-11-05\"},"
                        + "\"purpose\":{\"enabled\":true,\"text\":\" 风控建模 \"},"
                        + "\"territory\":{\"enabled\":false},"
                        + "\"noRedistribution\":{\"enabled\":true},\"noRestrictionDeclared\":false}");
        final JsonNode stored = result.policy().toJson();
        assertThat(UsagePolicyDslParser.parseTolerant(stored)).isEqualTo(result.policy());
    }
}
