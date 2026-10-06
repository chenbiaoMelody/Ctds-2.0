package com.ctds.contract.domain.policy;

import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.UsageControlPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用控制策略 DSL 解析器（WBS-3.4.4 hifi §4，校验规则单点 R1~R8——3.4.3 三件校验
 * 〔结构/取值/确认门槛〕收敛迁入，hifi §10-7"禁止两处并行定义"兑现）。
 *
 * <p>提交路径 {@code parse} 严格全查（版本门槛 / 未知键 / 结构 / 取值 / 互斥 / 规范化 /
 * 时点）；读路径 {@code parseTolerant} 容忍解析（密文回读——缺省版本 = 1.0、缺字段 =
 * 禁用，不回溯提交时点校验；未知版本 fail-closed 拒绝解析，不冒充空策略）；
 * {@code satisfiesConfirmGate} 确认锁定门槛（W7 应用服务单点调用）。违规明细由调用方
 * 入服务端日志并落 1008C0015（响应仅常量文案，沿 C0004/C0015 先例）。</p>
 *
 * <p>校验规则表（hifi §4.2；全部拒绝 → 1008C0015）：R1 文档形态 / R2 字段类型与未知要素
 * 封闭集 / R3 版本门槛（缺省容忍 = 1.0、未知值 fail-closed）/ R4 次数取值（≥1 整数）/
 * R5 期限取值与起始不早于提交日 / R6 文本 trim 后非空（规范化后落模）/ R7 互斥
 * （声明与任一要素启用不得并存）/ R8 确认门槛。</p>
 */
public final class UsagePolicyDslParser {

    /** 解析结果：violations 为空 = 合法（policy 为规范化后模型）；非空 = 拒绝（policy = 空基准态）。 */
    public record ParseResult(UsageControlPolicy policy, List<String> violations) {

        public boolean isValid() {
            return violations.isEmpty();
        }
    }

    private UsagePolicyDslParser() {
    }

    /** 提交路径严格解析（R1~R7 全查；R5 时点基准 = 当日）。 */
    public static ParseResult parse(final JsonNode document) {
        return parse(document, LocalDate.now());
    }

    /** 提交路径严格解析（R5 时点基准显式注入——时点判定的可测单点，消除系统时钟脆性）。 */
    public static ParseResult parse(final JsonNode document, final LocalDate submissionDate) {
        final List<String> violations = new ArrayList<>();
        if (document == null || document.isNull()) {
            // 请求未携策略节点 = 空策略（确认锁定门槛兜住——行为 4 规则 2）
            return new ParseResult(UsageControlPolicy.empty(), violations);
        }
        if (!document.isObject()) {
            violations.add("strategy 须为对象");
            return new ParseResult(UsageControlPolicy.empty(), violations);
        }
        checkVersion(document, violations);
        checkUnknownFields(document, violations);
        final boolean declared = readDeclaration(document, violations);
        final UsageControlPolicy.Element quota =
                readQuota(document, violations);
        final UsageControlPolicy.Element term = readTerm(document, submissionDate, violations);
        final UsageControlPolicy.Element purpose =
                readTextElement(document, UsagePolicyDsl.FIELD_PURPOSE, "用途文本不能为空",
                        violations);
        final UsageControlPolicy.Element territory =
                readTextElement(document, UsagePolicyDsl.FIELD_TERRITORY, "域内文本不能为空",
                        violations);
        final UsageControlPolicy.Element noRedistribution =
                readFlagElement(document, UsagePolicyDsl.FIELD_NO_REDISTRIBUTION, violations);
        // R7 互斥："无使用限制"声明与任一限制要素并存 = 语义矛盾（行为 4 规则 2 语义推论）
        if (declared && (quota.enabled() || term.enabled() || purpose.enabled()
                || territory.enabled() || noRedistribution.enabled())) {
            violations.add("无使用限制声明与启用的限制要素互斥");
        }
        if (!violations.isEmpty()) {
            // 拒绝态不落半解析模型（违规以明细为准，调用方落 C0015）
            return new ParseResult(UsageControlPolicy.empty(), violations);
        }
        return new ParseResult(new UsageControlPolicy(quota, term, purpose, territory,
                noRedistribution, declared), violations);
    }

    /** 读路径容忍解析（密文回读 / 引擎判定——缺省版本 = 1.0，缺字段 = 禁用；不做提交时点校验）。 */
    public static UsageControlPolicy parseTolerant(final JsonNode document) {
        if (document == null || !document.isObject()) {
            return UsageControlPolicy.empty();
        }
        final JsonNode version = document.get(UsagePolicyDsl.FIELD_DSL_VERSION);
        if (version != null && !version.isNull()
                && (!version.isTextual() || !UsagePolicyDsl.DSL_VERSION.equals(version.asText()))) {
            // fail-closed：未知版本拒绝解析（不冒充空策略——兼容映射矩阵 §5）
            throw new ContractBizException(ContractErrorCodes.POLICY_CLAUSE_INVALID,
                    ContractErrorCodes.POLICY_CLAUSE_INVALID_MESSAGE);
        }
        return new UsageControlPolicy(
                tolerantElement(document.get(UsagePolicyDsl.FIELD_QUOTA)),
                tolerantElement(document.get(UsagePolicyDsl.FIELD_TERM)),
                tolerantElement(document.get(UsagePolicyDsl.FIELD_PURPOSE)),
                tolerantElement(document.get(UsagePolicyDsl.FIELD_TERRITORY)),
                tolerantElement(document.get(UsagePolicyDsl.FIELD_NO_REDISTRIBUTION)),
                document.path(UsagePolicyDsl.FIELD_NO_RESTRICTION_DECLARED).asBoolean(false));
    }

    /** 确认锁定门槛（R8，行为 4 规则 2——3.4.3 hasAnyRestrictionOrDeclared 迁入单点）。 */
    public static boolean satisfiesConfirmGate(final UsageControlPolicy policy) {
        return policy != null && (policy.quota().enabled() || policy.term().enabled()
                || policy.purpose().enabled() || policy.territory().enabled()
                || policy.noRedistribution().enabled() || policy.noRestrictionDeclared());
    }

    // ==== R3 版本门槛（缺省容忍 = 1.0；未知值 fail-closed）====

    private static void checkVersion(final JsonNode document, final List<String> violations) {
        final JsonNode version = document.get(UsagePolicyDsl.FIELD_DSL_VERSION);
        if (version == null || version.isNull()) {
            return;
        }
        if (!version.isTextual()) {
            violations.add("dslVersion 须为字符串");
            return;
        }
        if (!UsagePolicyDsl.DSL_VERSION.equals(version.asText())) {
            violations.add("不支持的 dslVersion: " + version.asText());
        }
    }

    // ==== R2 未知要素封闭集（目录 fields() 为据——封闭性由目录承载）====

    private static void checkUnknownFields(final JsonNode document, final List<String> violations) {
        final List<String> unknown = new ArrayList<>();
        document.fieldNames().forEachRemaining(name -> {
            if (name.equals(UsagePolicyDsl.FIELD_DSL_VERSION)
                    || name.equals(UsagePolicyDsl.FIELD_NO_RESTRICTION_DECLARED)
                    || PolicyElementCatalog.fields().contains(name)) {
                return;
            }
            unknown.add(name);
        });
        for (final String name : unknown) {
            violations.add("未知策略要素: " + name);
        }
    }

    private static boolean readDeclaration(final JsonNode document, final List<String> violations) {
        final JsonNode declared = document.get(UsagePolicyDsl.FIELD_NO_RESTRICTION_DECLARED);
        if (declared != null && !declared.isNull() && !declared.isBoolean()) {
            violations.add("noRestrictionDeclared 须为布尔");
        }
        return declared != null && declared.asBoolean(false);
    }

    /**
     * 要素启用形态（R2）：缺省/空 = 禁用；非对象或缺 enabled 布尔 = 违规且按禁用落位。
     */
    private static boolean enabledElement(final JsonNode node, final String field,
            final List<String> violations) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isObject() || !node.has(UsagePolicyDsl.FIELD_ENABLED)
                || !node.get(UsagePolicyDsl.FIELD_ENABLED).isBoolean()) {
            violations.add(field + " 须为含 enabled 布尔的对象");
            return false;
        }
        return node.get(UsagePolicyDsl.FIELD_ENABLED).asBoolean();
    }

    // ==== R2 + R4 次数要素（3.4.3 结构校验与取值校验合一）====

    private static UsageControlPolicy.Element readQuota(final JsonNode document,
            final List<String> violations) {
        final JsonNode node = document.get(UsagePolicyDsl.FIELD_QUOTA);
        if (!enabledElement(node, UsagePolicyDsl.FIELD_QUOTA, violations)) {
            return UsageControlPolicy.Element.flag(false);
        }
        final JsonNode maxCount = node.get(UsagePolicyDsl.FIELD_MAX_COUNT);
        if (maxCount == null || !maxCount.isInt()) {
            violations.add("quota.maxCount 须为整数");
            return UsageControlPolicy.Element.ofCount(true, null);
        }
        if (maxCount.asInt() < 1) {
            violations.add("次数须为正整数");
        }
        return UsageControlPolicy.Element.ofCount(true, maxCount.asInt());
    }

    // ==== R2 + R5 期限要素（起止有序 + 起始日 ≥ 提交日）====

    private static UsageControlPolicy.Element readTerm(final JsonNode document,
            final LocalDate submissionDate, final List<String> violations) {
        final JsonNode node = document.get(UsagePolicyDsl.FIELD_TERM);
        if (!enabledElement(node, UsagePolicyDsl.FIELD_TERM, violations)) {
            return UsageControlPolicy.Element.flag(false);
        }
        final JsonNode startNode = node.get(UsagePolicyDsl.FIELD_START_DATE);
        final JsonNode endNode = node.get(UsagePolicyDsl.FIELD_END_DATE);
        if (startNode == null || !startNode.isTextual()
                || endNode == null || !endNode.isTextual()) {
            violations.add("term 起止须为字符串日期");
            return UsageControlPolicy.Element.ofTerm(true, textOrNull(startNode),
                    textOrNull(endNode));
        }
        final String startText = startNode.asText();
        final String endText = endNode.asText();
        final LocalDate start = parseDate(startText, "期限起始", violations);
        final LocalDate end = parseDate(endText, "期限截止", violations);
        if (start != null && end != null && end.isBefore(start)) {
            violations.add("期限起止倒置");
        }
        if (start != null && start.isBefore(submissionDate)) {
            // 规格"期限早于生效日"在提交时点的可判定化（提交时生效日未知，唯一可判定非法形态）
            violations.add("期限起始日不得早于提交日");
        }
        return UsageControlPolicy.Element.ofTerm(true, startText, endText);
    }

    // ==== R2 + R6 文本要素（trim 后非空；规范化后落模）====

    private static UsageControlPolicy.Element readTextElement(final JsonNode document,
            final String field, final String blankMessage, final List<String> violations) {
        final JsonNode node = document.get(field);
        if (!enabledElement(node, field, violations)) {
            return UsageControlPolicy.Element.flag(false);
        }
        final JsonNode text = node.get(UsagePolicyDsl.FIELD_TEXT);
        if (text == null || !text.isTextual()) {
            violations.add(field + ".text 须为字符串");
            return UsageControlPolicy.Element.ofText(true, null);
        }
        final String trimmed = text.asText().trim();
        if (trimmed.isEmpty()) {
            violations.add(blankMessage);
            return UsageControlPolicy.Element.ofText(true, null);
        }
        return UsageControlPolicy.Element.ofText(true, trimmed);
    }

    // ==== R2 纯开关要素（禁止再分发）====

    private static UsageControlPolicy.Element readFlagElement(final JsonNode document,
            final String field, final List<String> violations) {
        return UsageControlPolicy.Element.flag(enabledElement(document.get(field), field,
                violations));
    }

    // ==== 读路径映射（3.4.3 fromJson 容忍读语义迁入——缺字段 = 禁用/空值）====

    private static UsageControlPolicy.Element tolerantElement(final JsonNode node) {
        if (node == null || !node.isObject()
                || !node.path(UsagePolicyDsl.FIELD_ENABLED).asBoolean(false)) {
            return UsageControlPolicy.Element.flag(false);
        }
        return new UsageControlPolicy.Element(true,
                node.hasNonNull(UsagePolicyDsl.FIELD_MAX_COUNT)
                        ? node.get(UsagePolicyDsl.FIELD_MAX_COUNT).asInt() : null,
                node.hasNonNull(UsagePolicyDsl.FIELD_START_DATE)
                        ? node.get(UsagePolicyDsl.FIELD_START_DATE).asText() : null,
                node.hasNonNull(UsagePolicyDsl.FIELD_END_DATE)
                        ? node.get(UsagePolicyDsl.FIELD_END_DATE).asText() : null,
                node.hasNonNull(UsagePolicyDsl.FIELD_TEXT)
                        ? node.get(UsagePolicyDsl.FIELD_TEXT).asText() : null);
    }

    private static String textOrNull(final JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static LocalDate parseDate(final String value, final String label,
            final List<String> violations) {
        if (value == null || value.isBlank()) {
            violations.add(label + "不能为空");
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (final DateTimeParseException e) {
            violations.add(label + "格式须为 YYYY-MM-DD");
            return null;
        }
    }
}
