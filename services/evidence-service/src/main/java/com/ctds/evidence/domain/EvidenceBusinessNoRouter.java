package com.ctds.evidence.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 业务单据号 → 跨域留痕锚点反解器（WBS-3.8.4 hifi §2；Q1-A 五类前缀封闭，生成点 = 3.8.3 五处埋点）：
 * SPACE{s}:ADMISSION{a} / SPACE{s}:POLICY-AL{g} → 空间域两锚点；CO###### → 合约域；
 * DL###### → 交付域；USG{12 位数字} → 合约域使用放行留痕。前缀不识别 → 空锚点
 * （时间线仅存证侧 + UNAVAILABLE 说明，不伪装连续）。
 */
public final class EvidenceBusinessNoRouter {

    /** 反解结果锚点（sealed：五类封闭，不可能出现第六类）。 */
    public sealed interface AuditAnchor {

        record SpaceAdmission(long spaceId, long admissionId) implements AuditAnchor {
        }

        record SpaceActionLog(long spaceId, long actionLogId) implements AuditAnchor {
        }

        record Contract(String contractNo) implements AuditAnchor {
        }

        record Usage(long usageLogId) implements AuditAnchor {
        }

        record Delivery(String deliveryNo) implements AuditAnchor {
        }
    }

    private static final Pattern SPACE_ADMISSION = Pattern.compile("^SPACE(\\d+):ADMISSION(\\d+)$");
    private static final Pattern SPACE_POLICY_AL = Pattern.compile("^SPACE(\\d+):POLICY-AL(\\d+)$");
    private static final Pattern CONTRACT_NO = Pattern.compile("^CO\\d{6}$");
    private static final Pattern USAGE_LOG = Pattern.compile("^USG\\d{12}$");
    private static final Pattern DELIVERY_NO = Pattern.compile("^DL\\d{6}$");

    private EvidenceBusinessNoRouter() {
    }

    /** 前缀反解；五类之外（含空 / 非法形态）返回 empty——调用方按"跨域段不可用"处理。 */
    public static Optional<AuditAnchor> route(final String businessNo) {
        if (businessNo == null) {
            return Optional.empty();
        }
        final Matcher admission = SPACE_ADMISSION.matcher(businessNo);
        if (admission.matches()) {
            return Optional.of(new AuditAnchor.SpaceAdmission(
                    Long.parseLong(admission.group(1)), Long.parseLong(admission.group(2))));
        }
        final Matcher policy = SPACE_POLICY_AL.matcher(businessNo);
        if (policy.matches()) {
            return Optional.of(new AuditAnchor.SpaceActionLog(
                    Long.parseLong(policy.group(1)), Long.parseLong(policy.group(2))));
        }
        if (CONTRACT_NO.matcher(businessNo).matches()) {
            return Optional.of(new AuditAnchor.Contract(businessNo));
        }
        final Matcher usage = USAGE_LOG.matcher(businessNo);
        if (usage.matches()) {
            return Optional.of(new AuditAnchor.Usage(Long.parseLong(businessNo.substring(3))));
        }
        if (DELIVERY_NO.matcher(businessNo).matches()) {
            return Optional.of(new AuditAnchor.Delivery(businessNo));
        }
        return Optional.empty();
    }
}
