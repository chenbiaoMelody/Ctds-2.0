package com.ctds.evidence.domain;

import com.ctds.common.errorcode.BizException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 存证编号形态（Q4-A）：{@code EV} + {@code yyyyMMdd} + 6 位当日序列（如 EV20261105000001）。
 * 序列号由取号表原子自增、不回收 → 编号终身不复用（规格 B1-AC2 强闭环）。
 */
public final class EvidenceNos {

    /** 当日序号上限 999999（6 位序列段）；超出按系统错误处置，不溢出编号。 */
    public static final int MAX_DAILY_SEQ = 999_999;

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private EvidenceNos() {
    }

    public static String format(final LocalDate businessDate, final int seq) {
        if (seq < 1 || seq > MAX_DAILY_SEQ) {
            throw new BizException(EvidenceErrorCodes.STORAGE_FAILED, "当日存证编号序号已达上限");
        }
        return "EV" + DATE.format(businessDate) + String.format("%06d", seq);
    }
}
