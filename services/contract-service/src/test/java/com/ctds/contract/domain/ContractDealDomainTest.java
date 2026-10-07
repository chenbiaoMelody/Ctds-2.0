package com.ctds.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.contract.domain.policy.UsagePolicyDslParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 协商与签署领域单测锚（WBS-3.4.3 hifi §7 U 锚）：状态机 Guard 全转移表（六态 × 七动作，
 * 终态零出边）；策略条款两道校验矩阵（提交基础取值 / 确认门槛）；条款值解析与框架校验矩阵；
 * 规范化稳定性（同值不同键序 → 同哈希；数值/日期边界）；变更明细 from→to。
 */
class ContractDealDomainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Sm3Service SM3 = new Sm3Service();
    /** 提交时点基准（固定注入——3.4.4 起时点校验归解析器单点，测试不以系统时钟为据）。 */
    private static final LocalDate SUBMISSION_DAY = LocalDate.of(2026, 1, 1);

    // ==== 状态机全转移表（行为 6 规则 1/2）====

    @Test
    void transitionTableCoversAllStatusesAndActions() {
        for (final ContractStatus status : ContractStatus.values()) {
            assertThat(ContractTransitions.allowed(LifecycleAction.PROPOSE, status))
                    .as("PROPOSE @ %s", status).isEqualTo(status == ContractStatus.NEGOTIATING);
            assertThat(ContractTransitions.allowed(LifecycleAction.CONFIRM, status))
                    .as("CONFIRM @ %s", status).isEqualTo(status == ContractStatus.NEGOTIATING);
            assertThat(ContractTransitions.allowed(LifecycleAction.NEGOTIATION_TERMINATE, status))
                    .as("TERMINATE @ %s", status).isEqualTo(status == ContractStatus.NEGOTIATING);
            assertThat(ContractTransitions.allowed(LifecycleAction.SIGN, status))
                    .as("SIGN @ %s", status).isEqualTo(status == ContractStatus.PENDING_SIGNATURE
                            || status == ContractStatus.PARTIALLY_SIGNED);
            assertThat(ContractTransitions.allowed(LifecycleAction.REFUSE_SIGN, status))
                    .as("REFUSE @ %s", status).isEqualTo(status == ContractStatus.PENDING_SIGNATURE
                            || status == ContractStatus.PARTIALLY_SIGNED);
            assertThat(ContractTransitions.allowed(LifecycleAction.RELEASE_CONSENT, status))
                    .as("RELEASE @ %s", status).isEqualTo(status == ContractStatus.EFFECTIVE);
            assertThat(ContractTransitions.allowed(LifecycleAction.FORCE_TERMINATE, status))
                    .as("FORCE @ %s", status).isEqualTo(status == ContractStatus.EFFECTIVE);
        }
    }

    @Test
    void finalStatesHaveNoOutboundEdge() {
        assertThat(ContractStatus.COMPLETED.isFinal()).isTrue();
        assertThat(ContractStatus.TERMINATED.isFinal()).isTrue();
        for (final ContractStatus status : ContractStatus.values()) {
            if (status.isFinal()) {
                for (final LifecycleAction action : LifecycleAction.values()) {
                    assertThat(ContractTransitions.allowed(action, status))
                            .as("终态 %s 对 %s 必须零出边", status, action).isFalse();
                }
            }
        }
    }

    // ==== 策略条款：提交时基础取值（行为 4 规则 6，Q7-A）====

    @Test
    void policyBasicValueViolationsMatrix() {
        // 次数：启用但缺值/非正 → 违规；合法正整数 → 通过
        assertThat(quotaPolicy(UsageControlPolicy.Element.ofCount(true, null))).isNotEmpty();
        assertThat(quotaPolicy(UsageControlPolicy.Element.ofCount(true, 0))).isNotEmpty();
        assertThat(quotaPolicy(UsageControlPolicy.Element.ofCount(true, -5))).isNotEmpty();
        assertThat(quotaPolicy(UsageControlPolicy.Element.ofCount(true, 1))).isEmpty();
        // 期限：格式非法 / 起止倒置 → 违规；起止有序（含同日）→ 通过
        assertThat(termPolicy(UsageControlPolicy.Element.ofTerm(true, "2026/01/01", "2027-01-01")))
                .isNotEmpty();
        assertThat(termPolicy(UsageControlPolicy.Element.ofTerm(true, "2027-01-01", "2026-01-01")))
                .isNotEmpty();
        assertThat(termPolicy(UsageControlPolicy.Element.ofTerm(true, "2026-01-01", "2026-01-01")))
                .isEmpty();
        // 文本要素：启用但空白 → 违规；非空 → 通过
        assertThat(textPolicy(UsageControlPolicy.Element.ofText(true, "  "))).isNotEmpty();
        assertThat(textPolicy(UsageControlPolicy.Element.ofText(true, "城市交通分析"))).isEmpty();
        // 未启用要素不校验取值
        assertThat(quotaPolicy(UsageControlPolicy.Element.ofCount(false, -5))).isEmpty();
    }

    /** 单要素策略文档 → 解析器单点违规清单（WBS-3.4.4 起校验职责收敛至解析器）。 */
    private List<String> quotaPolicy(final UsageControlPolicy.Element quota) {
        return strategyViolationsOf("quota", quota);
    }

    private List<String> termPolicy(final UsageControlPolicy.Element term) {
        return strategyViolationsOf("term", term);
    }

    private List<String> textPolicy(final UsageControlPolicy.Element purpose) {
        return strategyViolationsOf("purpose", purpose);
    }

    private List<String> strategyViolationsOf(final String field,
            final UsageControlPolicy.Element element) {
        final ObjectNode strategy = MAPPER.createObjectNode();
        strategy.set(field, elementJson(element));
        return UsagePolicyDslParser.parse(strategy, SUBMISSION_DAY).violations();
    }

    private static ObjectNode elementJson(final UsageControlPolicy.Element element) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put("enabled", element.enabled());
        if (element.enabled()) {
            if (element.maxCount() != null) {
                node.put("maxCount", element.maxCount());
            }
            if (element.startDate() != null) {
                node.put("startDate", element.startDate());
            }
            if (element.endDate() != null) {
                node.put("endDate", element.endDate());
            }
            if (element.text() != null) {
                node.put("text", element.text());
            }
        }
        return node;
    }

    // ==== 策略条款：确认锁定门槛（行为 4 规则 2）====

    @Test
    void confirmationGateRequiresAnyElementOrExplicitDeclaration() {
        assertThat(UsagePolicyDslParser
                .satisfiesConfirmGate(UsageControlPolicy.empty())).isFalse();
        assertThat(UsagePolicyDslParser.satisfiesConfirmGate(
                new UsageControlPolicy(UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false),
                        UsageControlPolicy.Element.flag(false), true))).isTrue();
        assertThat(quotaPolicy(UsageControlPolicy.Element.ofCount(true, 100)).size()).isEqualTo(0);
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

    @Test
    void policyJsonRoundTripAndFixedFieldOrder() throws Exception {
        final UsageControlPolicy origin = new UsageControlPolicy(
                UsageControlPolicy.Element.ofCount(true, 100),
                UsageControlPolicy.Element.ofTerm(true, "2026-10-05", "2027-10-04"),
                UsageControlPolicy.Element.ofText(true, "城市交通分析"),
                UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(true), false);
        final String json = origin.toJson().toString();
        // 固定字段序（规范化不依赖用户键序——hifi §6.2）
        assertThat(json).isEqualTo("{\"quota\":{\"enabled\":true,\"maxCount\":100},"
                + "\"term\":{\"enabled\":true,\"startDate\":\"2026-10-05\",\"endDate\":\"2027-10-04\"},"
                + "\"purpose\":{\"enabled\":true,\"text\":\"城市交通分析\"},"
                + "\"territory\":{\"enabled\":false},"
                + "\"noRedistribution\":{\"enabled\":true},\"noRestrictionDeclared\":false}");
        final UsageControlPolicy back = UsagePolicyDslParser.parseTolerant(MAPPER.readTree(json));
        assertThat(back).isEqualTo(origin);
        // 缺字段容忍读 = 禁用/空值
        assertThat(UsagePolicyDslParser.parseTolerant(null)).isEqualTo(UsageControlPolicy.empty());
    }

    // ==== 条款值解析与框架校验（1008C0014 / 1008C0015 两桶）====

    @Test
    void parseFlagsNonTextualSlotValuesIntoClauseBucket() throws Exception {
        final JsonNode root = MAPPER.readTree(
                "{\"slots\":{\"a\":\"文本\",\"b\":123},\"strategy\":{\"noRestrictionDeclared\":true}}");
        final ClauseValues.Parsed parsed = ClauseValues.parse(root);
        assertThat(parsed.hasSlotViolations()).isTrue();
        assertThat(parsed.slotViolations()).anyMatch(v -> v.contains("b"));
        assertThat(parsed.hasStrategyViolations()).isFalse();
        assertThat(parsed.values().slots()).containsEntry("a", "文本").doesNotContainKey("b");
    }

    @Test
    void parseFlagsBadStrategyShapeIntoPolicyBucket() throws Exception {
        final JsonNode root = MAPPER.readTree(
                "{\"slots\":{},\"strategy\":{\"quota\":{\"enabled\":true,\"maxCount\":\"x\"}}}");
        final ClauseValues.Parsed parsed = ClauseValues.parse(root);
        assertThat(parsed.hasStrategyViolations()).isTrue();
        assertThat(parsed.hasSlotViolations()).isFalse();
    }

    @Test
    void frameworkViolationsCatchMissingRequiredAndUnknownKeys() throws Exception {
        final String framework = "{\"slots\":[{\"key\":\"a\",\"required\":true},"
                + "{\"key\":\"b\",\"required\":false},{\"key\":\"c\",\"required\":true}]}";
        final ClauseValues values = valuesOf("{\"a\":\"值A\",\"x\":\"未知槽\"}");
        final List<String> violations = values.frameworkViolations(framework);
        assertThat(violations).anyMatch(v -> v.contains("c"));
        assertThat(violations).anyMatch(v -> v.contains("x"));
        assertThat(valuesOf("{\"a\":\"值A\",\"c\":\"值C\"}").frameworkViolations(framework))
                .isEmpty();
    }

    private ClauseValues valuesOf(final String slotsJson) throws Exception {
        return ClauseValues.parse(MAPPER.readTree(
                "{\"slots\":" + slotsJson + ",\"strategy\":{\"noRestrictionDeclared\":true}}"))
                .values();
    }

    // ==== 变更明细（行为 2 规则 4：从何值→到何值）====

    @Test
    void diffProducesFromToPerSlotAndWholeStrategy() throws Exception {
        final ClauseValues previous = valuesOf("{\"a\":\"旧值\",\"b\":\"保留\",\"c\":\"将删\"}");
        // 下一版策略取值变化（声明 → 次数 100）→ strategy 整体 from→to 一并入明细
        final ClauseValues next = ClauseValues.parse(MAPPER.readTree(
                "{\"slots\":{\"a\":\"新值\",\"b\":\"保留\",\"d\":\"新增\"},"
                        + "\"strategy\":{\"quota\":{\"enabled\":true,\"maxCount\":100}}}")).values();
        final List<ClauseValues.Change> changes = ClauseValues.diffAgainst(previous, next);
        assertThat(changes).containsSubsequence(new ClauseValues.Change("a", "旧值", "新值"),
                new ClauseValues.Change("c", "将删", null),
                new ClauseValues.Change("d", null, "新增"));
        assertThat(changes).anyMatch(change -> "strategy".equals(change.slot()));
        final String json = ClauseValues.changesJson(changes).toString();
        assertThat(json).contains("\"slot\":\"a\"").contains("\"from\":\"旧值\"")
                .contains("\"to\":\"新值\"").contains("\"slot\":\"strategy\"");
    }

    // ==== 规范化稳定性（hifi §6.2）====

    @Test
    void canonicalizerIsStableAcrossSlotKeyOrder() {
        final Contract contract = contractOf();
        final ClauseValues first = new ClauseValues(new java.util.TreeMap<>(
                java.util.Map.of("scope", "内部数据分析", "subject_matter", "数据集X")),
                UsageControlPolicy.empty());
        // 相同键值对不同插入次序（HashMap 语义）→ 同一规范化 JSON → 同哈希
        final java.util.Map<String, String> reversed = new java.util.HashMap<>();
        reversed.put("subject_matter", "数据集X");
        reversed.put("scope", "内部数据分析");
        final ClauseValues second = new ClauseValues(reversed, UsageControlPolicy.empty());
        final String canonicalFirst = ContractCanonicalizer.canonicalJson(contract, first);
        final String canonicalSecond = ContractCanonicalizer.canonicalJson(contract, second);
        assertThat(canonicalFirst).isEqualTo(canonicalSecond);
        assertThat(ContractCanonicalizer.contentHash(canonicalFirst, SM3))
                .isEqualTo(ContractCanonicalizer.contentHash(canonicalSecond, SM3))
                .hasSize(64);
        // 任一槽位值变化 → 哈希必变
        final ClauseValues altered = new ClauseValues(
                new java.util.TreeMap<>(java.util.Map.of("scope", "内部数据分析",
                        "subject_matter", "数据集Y")), UsageControlPolicy.empty());
        assertThat(ContractCanonicalizer.contentHash(
                ContractCanonicalizer.canonicalJson(contract, altered), SM3))
                .isNotEqualTo(ContractCanonicalizer.contentHash(canonicalFirst, SM3));
        // 定价 plain 字符串承载（快照原值原标度，确定性序列化）
        final Contract priced = new Contract(null, "CO000001", 5L, "产品", "P", "R",
                "CT000001", 1, "PER_CALL", new BigDecimal("1.10"), 1, ContractStatus.NEGOTIATING,
                null, null, null, null, null, null, null, "R", null, null);
        assertThat(ContractCanonicalizer.canonicalJson(priced, first)).contains("\"priceAmount\":\"1.10\"");
    }

    private Contract contractOf() {
        return new Contract(null, "CO000001", 5L, "产品", "P", "R", "CT000001", 1, "FREE",
                null, 1, ContractStatus.NEGOTIATING, null, null, null, null, null, null, null,
                "R", null, null);
    }

    // ==== 参与方判定与对手方 ====

    @Test
    void roleOfResolvesBothPartiesOnly() {
        final Contract contract = contractOf();
        assertThat(contract.roleOf("P")).isEqualTo(PartyRole.PROVIDER);
        assertThat(contract.roleOf("R")).isEqualTo(PartyRole.REQUESTER);
        assertThat(contract.roleOf("S-other")).isNull();
        assertThat(contract.roleOf(null)).isNull();
        assertThat(PartyRole.PROVIDER.counterparty()).isEqualTo(PartyRole.REQUESTER);
        assertThat(PartyRole.REQUESTER.counterparty()).isEqualTo(PartyRole.PROVIDER);
    }

    @Test
    void clauseVersionConfirmationAccessors() {
        final LocalDateTime at = LocalDateTime.of(2026, 10, 5, 12, 0);
        final ContractClauseVersion version = new ContractClauseVersion(1L, 2L, 1,
                new byte[] {1}, null, null, null, "R", at, at, null);
        assertThat(version.confirmedAt(PartyRole.PROVIDER)).isEqualTo(at);
        assertThat(version.confirmedAt(PartyRole.REQUESTER)).isNull();
        assertThat(version.bothConfirmed()).isFalse();
    }
}
