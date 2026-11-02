package com.ctds.metering.application.billing;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * 出账周期口径（hifi §3.2/§4）：自然月 "YYYY-MM"，与计量 R-M2 的
 * DATE_FORMAT(occurred_at,'%Y-%m') 同口径；周期边界按库 / 服务默认时区的本地时点。
 */
public final class BillingPeriods {

    private static final Pattern FORMAT = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private BillingPeriods() {
    }

    /** 解析并校验周期：格式非法 → 1000C0001（调用方已确认非空）。 */
    public static YearMonth parse(final String period) {
        if (period == null || !FORMAT.matcher(period).matches()) {
            throw paramInvalid();
        }
        try {
            return YearMonth.parse(period);
        } catch (final DateTimeParseException e) {
            throw paramInvalid();
        }
    }

    /** 周期初（当月 1 日 00:00:00）。 */
    public static LocalDateTime start(final YearMonth month) {
        return LocalDate.of(month.getYear(), month.getMonthValue(), 1).atStartOfDay();
    }

    /** 周期末（当月最后一日 23:59:59.999999999）。 */
    public static LocalDateTime end(final YearMonth month) {
        return LocalDate.of(month.getYear(), month.getMonthValue(), month.lengthOfMonth())
                .atTime(LocalTime.MAX);
    }

    /** 周期文本（YYYY-MM）。 */
    public static String text(final YearMonth month) {
        return String.format("%04d-%02d", month.getYear(), month.getMonthValue());
    }

    private static BizException paramInvalid() {
        return new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
    }
}
