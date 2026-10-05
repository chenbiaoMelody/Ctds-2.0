package com.ctds.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 条款框架值对象单元测试（WBS-3.4.2 hifi §7 领域锚；T9 校验逻辑单测层——集成层另有端到端）：
 * 结构非法 / 缺必填 / 未知槽位 / 重复槽位 / 字段超限逐类拒绝；合法框架（含可选槽位缺省）放行；
 * 稳定哈希同内容同哈希（键序不敏感）、异内容异哈希。框架 JSON 程序化构造（不依赖文本字面量缩进）。
 */
class ContractDomainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 公共骨架 7 必填槽位键（lofi §3.1）。 */
    private static final String[] COMMON = {"subject_matter", "scope", "term", "purpose_and_restrictions",
        "security_confidentiality", "liability", "dispute_resolution"};

    private static String slot(final String key, final boolean required) {
        return String.format("{\"key\":\"%s\",\"name\":\"n-%s\",\"required\":%b,\"guide\":\"g\"}",
                key, key, required);
    }

    /** 构造公共数据授权类框架：公共 7 + 差异化槽位（键数组，extra 为空 = 恰好必填集）。 */
    private static String framework(final String... extraKeys) {
        final StringBuilder sb = new StringBuilder("{\"slots\":[");
        for (int i = 0; i < COMMON.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(slot(COMMON[i], true));
        }
        for (final String key : extraKeys) {
            sb.append(",").append(slot(key, false));
        }
        return sb.append("]}").toString();
    }

    /** 公共数据授权合法框架（公共 7 + 差异化必填 2）。 */
    private static final String PUBLIC_AUTH_VALID =
            framework("data_format_delivery", "data_update_obligation");

    private List<String> violationReasons(final String json) throws Exception {
        return ClauseFramework.validate(TemplateType.PUBLIC_DATA_AUTHORIZATION,
                        MAPPER.readTree(json)).stream().map(v -> v.key() + ":" + v.reason()).toList();
    }

    @Test
    void validFrameworkPasses() throws Exception {
        assertThat(violationReasons(PUBLIC_AUTH_VALID)).isEmpty();
    }

    @Test
    void optionalSlotMayBePresentOrAbsent() throws Exception {
        // 可选槽位（data_quality_commitment）在则通过
        assertThat(violationReasons(framework("data_format_delivery", "data_update_obligation",
                "data_quality_commitment"))).isEmpty();
        // 缺省也通过（必填集 = 公共 7 + 类型必填 2，可选不参与必填判定）
        assertThat(violationReasons(PUBLIC_AUTH_VALID)).isEmpty();
    }

    @Test
    void missingRequiredSlotRejected() throws Exception {
        assertThat(violationReasons(framework("data_format_delivery")))
                .contains("data_update_obligation:必填槽位缺失");
    }

    @Test
    void unknownSlotKeyRejected() throws Exception {
        assertThat(violationReasons(framework("data_format_delivery", "data_update_obligation",
                "not_in_catalog"))).contains("not_in_catalog:未知槽位键");
    }

    @Test
    void duplicateSlotKeyRejected() throws Exception {
        assertThat(violationReasons(framework("data_format_delivery", "data_update_obligation",
                "term"))).contains("term:槽位键重复");
    }

    @Test
    void nonObjectAndEmptySlotsRejected() throws Exception {
        assertThat(violationReasons("[]")).contains("null:框架须为 JSON 对象");
        assertThat(violationReasons("{}")).contains("null:slots 须为非空数组");
        assertThat(violationReasons("{\"slots\":[]}")).contains("null:slots 须为非空数组");
    }

    @Test
    void oversizedGuideRejected() throws Exception {
        final String oversized = String.format(
                "{\"slots\":[{\"key\":\"subject_matter\",\"name\":\"n\",\"required\":true,"
                        + "\"guide\":\"%s\"}]}", "x".repeat(257));
        assertThat(violationReasons(oversized))
                .contains("subject_matter:填写说明缺失或超出长度限制");
    }

    @Test
    void oversizedKeyRejected() throws Exception {
        final String oversized = String.format(
                "{\"slots\":[{\"key\":\"%s\",\"name\":\"n\",\"required\":true,\"guide\":\"g\"}]}",
                "k".repeat(65));
        assertThat(violationReasons(oversized)).contains("null:槽位键缺失或超出长度限制");
    }

    @Test
    void typeAllowedKeysDiffer() {
        assertThat(ClauseFramework.allowedKeys(TemplateType.API_CALL))
                .contains("rate_limit", "availability_commitment").doesNotContain("data_format_delivery");
        assertThat(ClauseFramework.allowedKeys(TemplateType.PRIVACY_COMPUTING))
                .contains("raw_data_not_leaving_domain", "audit_verification");
        assertThat(ClauseFramework.requiredKeys(TemplateType.PRIVACY_COMPUTING))
                .contains("raw_data_not_leaving_domain").doesNotContain("audit_verification");
    }

    @Test
    void stableHashIsOrderAndContentSensitive() throws Exception {
        // 同内容不同键序 → 同哈希（规范化后哈希）；异内容 → 异哈希；非法输入 → 固定值
        final String reordered = "{\"slots\":["
                + slot("scope", true) + "," + slot("subject_matter", true) + "]}";
        final String ordered = "{\"slots\":["
                + slot("subject_matter", true) + "," + slot("scope", true) + "]}";
        assertThat(ClauseFramework.stableHash(reordered)).isEqualTo(ClauseFramework.stableHash(ordered));
        assertThat(ClauseFramework.stableHash(ordered))
                .isNotEqualTo(ClauseFramework.stableHash(framework("scope")));
        assertThat(ClauseFramework.stableHash("not-json")).isEqualTo("invalid");
        assertThat(ClauseFramework.stableHash(null)).isEqualTo("invalid");
    }

    @Test
    void normalizerTrimsAndLowercases() {
        assertThat(TemplateNameNormalizer.normalize("  模板A ")).isEqualTo("模板a");
        assertThat(TemplateNameNormalizer.normalize(null)).isEmpty();
    }
}
