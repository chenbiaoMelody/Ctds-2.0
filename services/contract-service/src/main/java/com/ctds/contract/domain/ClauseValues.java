package com.ctds.contract.domain;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 条款值值对象（WBS-3.4.3 hifi §6.3）：槽位值（按锁定模板版本槽位框架填写）+ 策略条款
 * （{@link UsageControlPolicy}，独立结构化字段承载）。槽位校验（1008C0014）：必填槽位齐备、
 * 无未知槽位键、值为字符串、单值 ≤2000 字符、总量 ≤32768 字节；策略校验（1008C0015）：
 * 提交时基础取值合法、确认锁定时至少一项启用或显式声明。槽位键排序承载存储与规范化形态
 * （规范化不依赖用户键序，hifi §6.2）。
 */
public record ClauseValues(Map<String, String> slots, UsageControlPolicy strategy) {

    /** 单槽位值字符上限（hifi §6.3）。 */
    public static final int MAX_SLOT_VALUE_CHARS = 2000;
    /** 条款值总量字节上限（存储形态 UTF-8，hifi §6.3）。 */
    public static final int MAX_TOTAL_BYTES = 32_768;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 解析结果（slotViolations → 1008C0014 桶；strategyViolations → 1008C0015 桶——
     * 两桶互不吞并，响应仅常量文案，明细由调用方入服务端日志，沿 C0004 先例）。
     */
    public record Parsed(ClauseValues values, List<String> slotViolations,
            List<String> strategyViolations) {

        public boolean hasSlotViolations() {
            return !slotViolations.isEmpty();
        }

        public boolean hasStrategyViolations() {
            return !strategyViolations.isEmpty();
        }
    }

    /** 变更明细行（"从何值→到何值"；strategy 整体差异以 slot = "strategy" 承载）。 */
    public record Change(String slot, String from, String to) {
    }

    /** 密文回读（存储形态已校验合法，容忍读；供详情/版本历史/规范化重算解密后还原值对象）。 */
    public static ClauseValues fromStored(final JsonNode root) {
        final Map<String, String> slots = new TreeMap<>();
        final JsonNode slotsNode = root == null ? null : root.get("slots");
        if (slotsNode != null && slotsNode.isObject()) {
            slotsNode.fieldNames().forEachRemaining(name -> slots.put(name,
                    slotsNode.get(name).asText()));
        }
        return new ClauseValues(slots,
                UsageControlPolicy.fromJson(root == null ? null : root.get("strategy")));
    }

    /**
     * 严格形态解析（请求体 JsonNode → 值对象）：slots 每值必须字符串（非字符串 → C0014 桶）；
     * strategy 结构按五要素（结构/取值非法 → C0015 桶）。slots 缺省 = 空槽位（必填缺失由
     * 框架校验兜住）；strategy 缺省 = 全禁用且无声明（确认锁定门槛兜住）。
     */
    public static Parsed parse(final JsonNode root) {
        final List<String> slotViolations = new ArrayList<>();
        final List<String> strategyViolations = new ArrayList<>();
        if (root == null || !root.isObject()) {
            slotViolations.add("条款值须为对象");
            return new Parsed(null, slotViolations, strategyViolations);
        }
        final Map<String, String> slots = new TreeMap<>();
        final JsonNode slotsNode = root.get("slots");
        if (slotsNode != null && !slotsNode.isNull()) {
            if (!slotsNode.isObject()) {
                slotViolations.add("slots 须为对象");
            } else {
                slotsNode.fieldNames().forEachRemaining(name -> {
                    final JsonNode value = slotsNode.get(name);
                    if (value == null || !value.isTextual()) {
                        slotViolations.add("槽位值须为字符串: " + name);
                    } else {
                        final String text = value.asText();
                        if (text.length() > MAX_SLOT_VALUE_CHARS) {
                            slotViolations.add("槽位值超长: " + name);
                        } else {
                            slots.put(name, text);
                        }
                    }
                });
            }
        }
        final JsonNode strategyNode = root.get("strategy");
        final List<String> shapeViolations = UsageControlPolicy.shapeViolations(strategyNode);
        strategyViolations.addAll(shapeViolations);
        final UsageControlPolicy strategy = strategyViolations.isEmpty()
                ? UsageControlPolicy.fromJson(strategyNode)
                : UsageControlPolicy.empty();
        final ClauseValues values = new ClauseValues(slots, strategy);
        if (storageByteSize(values) > MAX_TOTAL_BYTES) {
            slotViolations.add("条款值总量超限");
        }
        return new Parsed(values, slotViolations, strategyViolations);
    }

    /**
     * 按锁定模板版本槽位框架校验（hifi §6.3；框架全文 = 3.4.2 QV2 loadFramework 锁定版本）：
     * 必填槽位齐备、无未知槽位键。框架 JSON 形态由 3.4.2 模板创建时保证（{"slots":[{key,required}]}）。
     */
    public List<String> frameworkViolations(final String frameworkJson) {
        final List<String> violations = new ArrayList<>();
        final JsonNode framework;
        try {
            framework = MAPPER.readTree(frameworkJson == null ? "" : frameworkJson);
        } catch (final JacksonException e) {
            violations.add("条款框架不可读");
            return violations;
        }
        final JsonNode definedSlots = framework.path("slots");
        if (!definedSlots.isArray()) {
            violations.add("条款框架不可读");
            return violations;
        }
        for (final JsonNode defined : definedSlots) {
            final String key = defined.path("key").asText();
            final boolean required = defined.path("required").asBoolean(false);
            final String value = slots().get(key);
            if (required && (value == null || value.isBlank())) {
                violations.add("缺失必填槽位: " + key);
            }
        }
        for (final String key : slots().keySet()) {
            boolean known = false;
            for (final JsonNode defined : definedSlots) {
                if (key.equals(defined.path("key").asText())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                violations.add("未知槽位键: " + key);
            }
        }
        return violations;
    }

    /** 存储形态 JSON（{"slots":{槽位键排序}, "strategy":{固定字段序}}；确定性序列化）。 */
    public ObjectNode toStorageJson() {
        final ObjectNode root = JsonNodeFactory.instance.objectNode();
        final ObjectNode slotsNode = root.putObject("slots");
        slots().forEach(slotsNode::put);
        root.set("strategy", strategy().toJson());
        return root;
    }

    /** 存储形态紧凑 JSON 文本（密文加密输入；总量口径同源）。 */
    public String toStorageText() {
        return toStorageJson().toString();
    }

    /**
     * 与上一版差异（变更明细，W6；V1 无上一版 = 密文列 NULL 不产生明细）：槽位逐键 from→to
     * （键序 = 双方键集排序并集；新增 from = null、移除 to = null）+ strategy 整体
     * from→to（slot = "strategy"，值为固定字段序紧凑 JSON）。
     */
    public static List<Change> diffAgainst(final ClauseValues previous, final ClauseValues next) {
        final List<Change> changes = new ArrayList<>();
        final Map<String, String> previousSlots = previous.slots();
        final Map<String, String> nextSlots = next.slots();
        final Map<String, String> union = new TreeMap<>(previousSlots);
        union.putAll(nextSlots);
        union.forEach((key, ignored) -> {
            final String from = previousSlots.get(key);
            final String to = nextSlots.get(key);
            if (!java.util.Objects.equals(from, to)) {
                changes.add(new Change(key, from, to));
            }
        });
        final String previousStrategy = previous.strategy().toJson().toString();
        final String nextStrategy = next.strategy().toJson().toString();
        if (!previousStrategy.equals(nextStrategy)) {
            changes.add(new Change("strategy", previousStrategy, nextStrategy));
        }
        return changes;
    }

    /** 变更明细 → JSON 数组（changes_cipher 加密输入；slot 键序 = 变更产生序）。 */
    public static ArrayNode changesJson(final List<Change> changes) {
        final ArrayNode array = JsonNodeFactory.instance.arrayNode();
        changes.forEach(change -> {
            final ObjectNode node = array.addObject();
            node.put("slot", change.slot());
            if (change.from() != null) {
                node.put("from", change.from());
            }
            if (change.to() != null) {
                node.put("to", change.to());
            }
        });
        return array;
    }

    private static long storageByteSize(final ClauseValues values) {
        return values.toStorageText().getBytes(StandardCharsets.UTF_8).length;
    }
}
