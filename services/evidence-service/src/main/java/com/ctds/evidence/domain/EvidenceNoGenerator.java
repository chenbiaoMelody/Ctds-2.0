package com.ctds.evidence.domain;

import java.time.LocalDate;

/**
 * 存证编号取号端口（Q4-A：EV + 日期 + 6 位当日序列，原子自增、不回收 → 终身不复用）。
 */
public interface EvidenceNoGenerator {

    /** 取当日下一个编号（同一业务日单调递增；跨日从 000001 重新开始）。 */
    String next(LocalDate businessDate);
}
