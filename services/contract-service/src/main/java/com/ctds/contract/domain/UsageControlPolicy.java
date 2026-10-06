package com.ctds.contract.domain;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 使用控制策略条款值对象（WBS-3.4.3 hifi §6.3 承载结构；WBS-3.4.4 hifi §4.3 收敛）：五要素
 * （次数 quota / 期限 term / 用途 purpose / 域内 territory / 禁止再分发 noRedistribution）
 * + 显式"无使用限制"声明（noRestrictionDeclared）。**本类为纯数据 + 序列化载体**——校验职责
 * （结构 / 取值 / 版本门槛 / 互斥 / 文本规范化 / 期限时点 / 确认门槛）全部收敛至校验单点
 * {@link com.ctds.contract.domain.policy.UsagePolicyDslParser}（禁止两处并行定义，兑现 3.4.3
 * hifi §10-7 衔接义务）。
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

    /** 全禁用且无声明（"策略空"基准态——请求未携 strategy 或提交载荷被拒时的落位）。 */
    public static UsageControlPolicy empty() {
        return new UsageControlPolicy(Element.flag(false), Element.flag(false), Element.flag(false),
                Element.flag(false), Element.flag(false), false);
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
}
