package com.ctds.contract.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用控制策略条款值对象（WBS-3.4.3 hifi §6.3，Q7-A 最小门槛口径；规格行为 4 规则 2/6）：
 * 五要素（次数 quota / 期限 term / 用途 purpose / 域内 territory / 禁止再分发 noRedistribution）
 * + 显式"无使用限制"声明（noRestrictionDeclared）。校验两道：**提交时**基础取值合法
 * （次数正整数 / 期限起止有序且格式合法 / 启用要素文本非空——规则 6）；**确认锁定时**至少
 * 一项启用或显式声明（规则 2）。完整 DSL 语法与空间策略模型形态对齐归 3.4.4（hifi §10-7
 * 交接登记：本结构为承载口径，其定稿演进由其设计并做兼容映射，禁止两处并行定义）。
 *
 * <p>序列化固定字段序（quota/term/purpose/territory/noRedistribution/noRestrictionDeclared），
 * 禁用要素仅落 enabled 布尔——规范化不依赖用户键序（hifi §6.2）。数值（次数）以 JSON number
 * 承载。</p>
 *
 * @param quota                  次数要素（maxCount 正整数）
 * @param term                   期限要素（ISO 本地日期起止）
 * @param purpose                用途要素（文本）
 * @param territory              域内要素（文本）
 * @param noRedistribution       禁止再分发要素（开关）
 * @param noRestrictionDeclared  显式"无使用限制"声明（行为 4 规则 2 的第二满足臂）
 */
public record UsageControlPolicy(Element quota, Element term, Element purpose, Element territory,
        Element noRedistribution, boolean noRestrictionDeclared) {

    /** 期限/文本等要素的统一载体（按要素语义取用字段；禁用要素仅 enabled = true 之外的字段为 null）。 */
    public record Element(boolean enabled, Integer maxCount, String startDate, String endDate,
            String text) {

        /** 纯开关要素（禁止再分发）。 */
        public static Element flag(final boolean enabled) {
            return new Element(enabled, null, null, null, null);
        }

        /** 数值要素（次数）。 */
        public static Element ofCount(final boolean enabled, final Integer maxCount) {
            return new Element(enabled, maxCount, null, null, null);
        }

        /** 期限要素。 */
        public static Element ofTerm(final boolean enabled, final String startDate,
                final String endDate) {
            return new Element(enabled, null, startDate, endDate, null);
        }

        /** 文本要素（用途/域内）。 */
        public static Element ofText(final boolean enabled, final String text) {
            return new Element(enabled, null, null, null, text);
        }
    }

    /** 全禁用且无声明（"策略空"基准态——请求未携 strategy 时的落位）。 */
    public static UsageControlPolicy empty() {
        return new UsageControlPolicy(Element.flag(false), Element.flag(false), Element.flag(false),
                Element.flag(false), Element.flag(false), false);
    }

    /**
     * 提交时基础取值校验（行为 4 规则 6，Q7-A；1008C0015 明细——响应仅常量文案，明细入服务端
     * 日志，沿 C0004 先例）。仅校验**启用中**要素的取值。
     */
    public List<String> basicValueViolations() {
        final List<String> violations = new ArrayList<>();
        if (quota.enabled() && (quota.maxCount() == null || quota.maxCount() < 1)) {
            violations.add("次数须为正整数");
        }
        if (term.enabled()) {
            final LocalDate start = parseDate(term.startDate(), "期限起始", violations);
            final LocalDate end = parseDate(term.endDate(), "期限截止", violations);
            if (start != null && end != null && end.isBefore(start)) {
                violations.add("期限起止倒置");
            }
        }
        if (purpose.enabled() && (purpose.text() == null || purpose.text().isBlank())) {
            violations.add("用途文本不能为空");
        }
        if (territory.enabled() && (territory.text() == null || territory.text().isBlank())) {
            violations.add("域内文本不能为空");
        }
        return violations;
    }

    /**
     * 确认锁定门槛（行为 4 规则 2，W7 应用服务单点；1008C0015）：至少一项要素启用 **或**
     * 显式"无使用限制"声明。
     */
    public boolean hasAnyRestrictionOrDeclared() {
        return quota.enabled() || term.enabled() || purpose.enabled() || territory.enabled()
                || noRedistribution.enabled() || noRestrictionDeclared;
    }

    /** 固定字段序紧凑 JSON（存储与规范化共用形态；禁用要素仅落 enabled）。 */
    public ObjectNode toJson() {
        final ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("quota", elementJson(quota, true, false));
        root.set("term", elementJson(term, false, true));
        root.set("purpose", elementJson(purpose, false, false));
        root.set("territory", elementJson(territory, false, false));
        root.set("noRedistribution", elementJson(noRedistribution, false, false));
        root.put("noRestrictionDeclared", noRestrictionDeclared);
        return root;
    }

    /** 容忍读（密文回读）：缺字段 = 禁用/空值，不抛错（存储层已校验合法）。 */
    public static UsageControlPolicy fromJson(final JsonNode strategy) {
        if (strategy == null || !strategy.isObject()) {
            return empty();
        }
        return new UsageControlPolicy(
                elementOf(strategy.get("quota")),
                elementOf(strategy.get("term")),
                elementOf(strategy.get("purpose")),
                elementOf(strategy.get("territory")),
                elementOf(strategy.get("noRedistribution")),
                strategy.path("noRestrictionDeclared").asBoolean(false));
    }

    /** 提交载荷严格校验（strategy 节点 → 违规明细；结构非法记违规由调用方落 C0015）。 */
    public static List<String> shapeViolations(final JsonNode strategy) {
        final List<String> violations = new ArrayList<>();
        if (strategy == null || strategy.isNull()) {
            return violations;
        }
        if (!strategy.isObject()) {
            violations.add("strategy 须为对象");
            return violations;
        }
        if (strategy.has("noRestrictionDeclared") && !strategy.get("noRestrictionDeclared").isBoolean()) {
            violations.add("noRestrictionDeclared 须为布尔");
        }
        for (final String name : new String[] {"quota", "term", "purpose", "territory",
                "noRedistribution"}) {
            final JsonNode element = strategy.get(name);
            if (element == null || element.isNull()) {
                continue;
            }
            if (!element.isObject() || !element.has("enabled")
                    || !element.get("enabled").isBoolean()) {
                violations.add(name + " 须为含 enabled 布尔的对象");
                continue;
            }
            if (!element.get("enabled").asBoolean()) {
                continue;
            }
            switch (name) {
                case "quota" -> {
                    if (!element.has("maxCount") || !element.get("maxCount").isInt()) {
                        violations.add("quota.maxCount 须为整数");
                    }
                }
                case "term" -> {
                    if (!element.has("startDate") || !element.get("startDate").isTextual()
                            || !element.has("endDate") || !element.get("endDate").isTextual()) {
                        violations.add("term 起止须为字符串日期");
                    }
                }
                case "purpose", "territory" -> {
                    if (!element.has("text") || !element.get("text").isTextual()) {
                        violations.add(name + ".text 须为字符串");
                    }
                }
                default -> {
                    // noRedistribution：纯开关，无附加字段
                }
            }
        }
        return violations;
    }

    private static Element elementOf(final JsonNode node) {
        if (node == null || !node.isObject() || !node.path("enabled").asBoolean(false)) {
            return Element.flag(false);
        }
        return new Element(true,
                node.hasNonNull("maxCount") ? node.get("maxCount").asInt() : null,
                node.hasNonNull("startDate") ? node.get("startDate").asText() : null,
                node.hasNonNull("endDate") ? node.get("endDate").asText() : null,
                node.hasNonNull("text") ? node.get("text").asText() : null);
    }

    private static ObjectNode elementJson(final Element element, final boolean withCount,
            final boolean withTerm) {
        final ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("enabled", element.enabled());
        if (element.enabled()) {
            if (withCount && element.maxCount() != null) {
                node.put("maxCount", element.maxCount());
            }
            if (withTerm) {
                if (element.startDate() != null) {
                    node.put("startDate", element.startDate());
                }
                if (element.endDate() != null) {
                    node.put("endDate", element.endDate());
                }
            }
            if (!withCount && !withTerm && element.text() != null) {
                node.put("text", element.text());
            }
        }
        return node;
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
