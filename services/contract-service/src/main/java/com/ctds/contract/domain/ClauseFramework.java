package com.ctds.contract.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 条款框架值对象（WBS-3.4.2 hifi §4/lofi §3；模板条款槽位集合 = 规格授权 3.4.2 落定的未定义项，
 * 2026-10-05 编排师"都按建议"确认：公共骨架 7 必填 + 三类差异化 2+1 / 2+1 / 3+1）。
 *
 * <p>框架 JSON 结构：{@code {"slots":[{"key","name","required","guide"}]}}——槽位 = 条款框架的
 * 填写骨架（发起合约时按槽位填写具体约定值），槽位本身不含任何真实数据；条款一致性由模板
 * 框架保证（行为 1 规则 6）。校验返回逐槽位违规明细（1008C0004 随响应返回）。</p>
 */
public final class ClauseFramework {

    /** 共享 ObjectMapper（只读序列化/解析，无配置注入需求；沿 catalog SemanticTags 静态先例）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 槽位键上限。 */
    public static final int MAX_KEY_LENGTH = 64;
    /** 槽位名称上限。 */
    public static final int MAX_NAME_LENGTH = 64;
    /** 填写说明上限。 */
    public static final int MAX_GUIDE_LENGTH = 256;

    /** 公共骨架槽位（三类模板共有，均为必填——lofi §3.1 确认清单）。 */
    private static final Set<String> COMMON_REQUIRED = Set.of(
            "subject_matter", "scope", "term", "purpose_and_restrictions",
            "security_confidentiality", "liability", "dispute_resolution");

    /** 类型差异化必填槽位（lofi §3.2 确认清单）。 */
    private static final Map<TemplateType, Set<String>> TYPE_REQUIRED = Map.of(
            TemplateType.PUBLIC_DATA_AUTHORIZATION, Set.of("data_format_delivery", "data_update_obligation"),
            TemplateType.API_CALL, Set.of("api_scope_and_invocation", "rate_limit"),
            TemplateType.PRIVACY_COMPUTING,
            Set.of("compute_env_security", "result_delivery", "raw_data_not_leaving_domain"));

    /** 类型差异化可选槽位（lofi §3.2 确认清单）。 */
    private static final Map<TemplateType, Set<String>> TYPE_OPTIONAL = Map.of(
            TemplateType.PUBLIC_DATA_AUTHORIZATION, Set.of("data_quality_commitment"),
            TemplateType.API_CALL, Set.of("availability_commitment"),
            TemplateType.PRIVACY_COMPUTING, Set.of("audit_verification"));

    /** 单槽位违规明细（key = 违规槽位键或 null〔结构级违规〕；reason = 违规原因，服务端常量）。 */
    public record SlotViolation(String key, String reason) {
    }

    private ClauseFramework() {
    }

    /** 该类型允许的槽位键全集（公共骨架 ∪ 类型必填 ∪ 类型可选）。 */
    public static Set<String> allowedKeys(final TemplateType type) {
        final Set<String> allowed = new LinkedHashSet<>(COMMON_REQUIRED);
        allowed.addAll(TYPE_REQUIRED.get(type));
        allowed.addAll(TYPE_OPTIONAL.get(type));
        return allowed;
    }

    /** 该类型的必填槽位键集合（公共骨架 + 类型差异化）。 */
    public static Set<String> requiredKeys(final TemplateType type) {
        final Set<String> required = new LinkedHashSet<>(COMMON_REQUIRED);
        required.addAll(TYPE_REQUIRED.get(type));
        return required;
    }

    /**
     * 校验条款框架（T9；行为 1 规则 6 前置）：
     * 结构合法（对象 + 非空 slots 数组）→ 逐槽位字段合法（key/name/guide 非空且不超限、required
     * 为布尔）→ 无重复槽位 → 无未知槽位键 → 必填槽位全覆盖。返回违规明细列表（空 = 合法）；
     * 明细为服务端常量，不回显用户输入原文（章程 4.3）。
     */
    public static List<SlotViolation> validate(final TemplateType type, final JsonNode root) {
        final List<SlotViolation> violations = new ArrayList<>();
        if (root == null || !root.isObject()) {
            violations.add(new SlotViolation(null, "框架须为 JSON 对象"));
            return violations;
        }
        final JsonNode slots = root.path("slots");
        if (!slots.isArray() || slots.isEmpty()) {
            violations.add(new SlotViolation(null, "slots 须为非空数组"));
            return violations;
        }
        final Set<String> seen = new LinkedHashSet<>();
        final Set<String> allowed = allowedKeys(type);
        for (final JsonNode slot : slots) {
            if (!slot.isObject()) {
                violations.add(new SlotViolation(null, "槽位须为 JSON 对象"));
                continue;
            }
            final String key = slot.path("key").asText(null);
            if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
                violations.add(new SlotViolation(null, "槽位键缺失或超出长度限制"));
                continue;
            }
            if (!seen.add(key)) {
                violations.add(new SlotViolation(key, "槽位键重复"));
                continue;
            }
            if (!allowed.contains(key)) {
                violations.add(new SlotViolation(key, "未知槽位键"));
                continue;
            }
            final String name = slot.path("name").asText(null);
            if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
                violations.add(new SlotViolation(key, "槽位名称缺失或超出长度限制"));
            }
            if (!slot.path("required").isBoolean()) {
                violations.add(new SlotViolation(key, "必填性须为布尔值"));
            }
            final String guide = slot.path("guide").asText(null);
            if (guide == null || guide.isBlank() || guide.length() > MAX_GUIDE_LENGTH) {
                violations.add(new SlotViolation(key, "填写说明缺失或超出长度限制"));
            }
        }
        for (final String required : requiredKeys(type)) {
            if (!seen.contains(required)) {
                violations.add(new SlotViolation(required, "必填槽位缺失"));
            }
        }
        return violations;
    }

    /**
     * 稳定哈希（修订幂等键成分，hifi §2.1 W2）：对 slots 按 key 排序后规范化再取 SHA-256
     * （同内容不同键序/空白 → 同哈希，重放语义正确）；非法 JSON 返回固定值"invalid"
     * （后续校验阶段必拒，不影响安全）。失败不抛异常（幂等键计算不得中断业务链）。
     */
    public static String stableHash(final String json) {
        try {
            final JsonNode root = MAPPER.readTree(json == null ? "" : json);
            if (!root.isObject()) {
                return "invalid";
            }
            final List<JsonNode> sorted = new ArrayList<>();
            root.path("slots").forEach(sorted::add);
            sorted.sort(Comparator.comparing(a -> a.path("key").asText("")));
            final ObjectNode canonical = MAPPER.createObjectNode();
            final ArrayNode array = canonical.putArray("slots");
            sorted.forEach(array::add);
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(MAPPER.writeValueAsBytes(canonical)));
        } catch (final NoSuchAlgorithmException | java.io.IOException | IllegalArgumentException e) {
            return "invalid";
        }
    }
}
