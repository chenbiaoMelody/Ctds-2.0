package com.ctds.space.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 留痕"何时"要素口径断言（WBS-3.2.7 T2，沿 did 域 {@code com.ctds.did.support.IsoSecondTimestamp} 先例）：
 * ISO-8601 本地时间形（无时区偏移）+ **秒级精度**。
 *
 * <p>口径来源（非新增约定，取自既有实现与库表）：留痕写入统一 {@code LocalDateTime.now(clock)}；
 * 库表 {@code space_action_log.created_at} 为 {@code DATETIME}（秒级，注释即"四要素'何时'"）。</p>
 *
 * <p>删锚验证（必红）：留痕时间改写为 epoch 毫秒、带小数秒或带时区偏移，断言即失败。</p>
 */
public final class IsoSecondTimestamp {

    /** ISO-8601 本地时间形：yyyy-MM-ddTHH:mm:ss（无小数秒、无时区偏移）。 */
    private static final Pattern SECOND_PRECISION = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

    private IsoSecondTimestamp() {
    }

    /** 断言：非空、ISO-8601 本地时间形、秒级精度（整体匹配，故小数秒/偏移/纯数字时间戳均判违规）。 */
    public static void assertSecondPrecisionIso(final String field, final String value) {
        assertThat(value).as("%s 非空", field).isNotBlank();
        assertThat(value).as("%s 必须为 ISO-8601 秒级本地时间形（无小数秒、无时区偏移）", field)
                .matches(SECOND_PRECISION);
        assertThat(LocalDateTime.parse(value)).as("%s 必须可被 ISO_LOCAL_DATE_TIME 解析（防伪格式）", field)
                .isNotNull();
    }
}
