package com.ctds.evidence.domain;

import com.ctds.common.errorcode.BizException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * 存证入参规则（hifi §1.1 / §1.2；规格 B1-1 / B1-2 / B6-2 口径）：
 * 值域封闭、长度与字符集守卫、事件时点区间守卫。全部抛 1012C0002（对外文案由出站映射统一）。
 */
public final class EvidenceRules {

    private static final Pattern BUSINESS_NO = Pattern.compile("^[A-Za-z0-9_.:-]{6,64}$");
    private static final Pattern SUBJECT_DID = Pattern.compile("^did:ctds:[A-Za-z0-9._:-]{6,96}$");
    private static final Pattern ACTOR = Pattern.compile("^[A-Za-z0-9_.-]{6,32}$");
    private static final Instant MIN_OCCURRED_AT = Instant.parse("2020-01-01T00:00:00Z");
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    private EvidenceRules() {
    }

    /** 业务单据号（与上游逐字一致：不 trim、不改大小写、不做归一）。 */
    public static String businessNo(final String raw) {
        if (raw == null || !BUSINESS_NO.matcher(raw).matches()) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "业务单据号不合法");
        }
        return raw;
    }

    /** 主体 DID（服务内只保存摘要）。 */
    public static String subjectDid(final String raw) {
        if (raw == null || !SUBJECT_DID.matcher(raw).matches()) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "主体 DID 不合法");
        }
        return raw;
    }

    /** 操作人 / 核验人主体编号（落留痕 actor_subject_no）。 */
    public static String actor(final String raw) {
        if (raw == null || !ACTOR.matcher(raw).matches()) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "操作人不合法");
        }
        return raw;
    }

    /**
     * 事件时点：ISO-8601 带偏移；拒绝 &gt; 当前时间 +5 分钟、拒绝 &lt; 2020-01-01。
     * 返回前统一截断到毫秒（DB-118）：摘要输入（{@code .SSS}）/ 落库列 {@code datetime(3)} / 出参
     * 三处同一口径——上游携带的亚毫秒精度若交给落库端四舍五入，会与摘要输入值不等而误报"不一致"。
     * 守卫按原始精度判定，截断只影响毫秒以下余量，不改时点区间口径。
     */
    public static Instant occurredAt(final String raw, final Clock clock) {
        final Instant value = instant(raw, "事件时点");
        final Instant now = clock.instant();
        if (value.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "事件时点不得晚于当前时间");
        }
        if (value.isBefore(MIN_OCCURRED_AT)) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "事件时点早于允许下限");
        }
        return value.truncatedTo(ChronoUnit.MILLIS);
    }

    /** 查询过滤时间（ISO-8601 带偏移；不做区间守卫，区间由调用方校验）。 */
    public static Instant instant(final String raw, final String field) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, field + "不合法");
        }
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (final DateTimeParseException e) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, field + "不合法");
        }
    }
}
