package com.ctds.contract.domain.policy;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 模拟通道假想上下文值对象（WBS-3.4.6 hifi §2 / §5）：模拟试算的"假设已用次数 + 假设判定日期"。
 * 缺省填充口径 = 真实计数 / 注入 Clock 当天（{@link #of}）；本类为纯数据载体——入参边界
 * （{@code 0 ≤ assumedUsedCount ≤ }{@link #MAX_ASSUMED_USED_COUNT}）由应用层校验并表达为
 * 1008C0008（hifi §5：纯函数不承担参数错误表达）。
 *
 * @param assumedUsedCount 假想已用次数（缺省填充后——真实 {@code used_count}）
 * @param assumedDate      假想判定日期（缺省填充后——注入 Clock 当天）
 */
public record SimulationContext(int assumedUsedCount, LocalDate assumedDate) {

    /** 假想计数上限（应用层校验口径；越界 = 参数非法 1008C0008）。 */
    public static final int MAX_ASSUMED_USED_COUNT = 1_000_000;

    public SimulationContext {
        Objects.requireNonNull(assumedDate, "assumedDate 必填");
    }

    /**
     * 缺省填充工厂（"假想上下文 = 真实状态"对照一致性锚的构造口径）：假想计数缺省 = 真实计数、
     * 假想日期缺省 = 注入 Clock 当天。
     *
     * @param assumedUsedCount 调用方假想计数（null = 取真实计数）
     * @param assumedDate      调用方假想日期（null = 取当天）
     * @param realUsedCount    真实已用次数（{@code UsageCounterStore.currentCount}）
     * @param today            注入 Clock 当天（禁止直取 {@code LocalDate.now()}）
     */
    public static SimulationContext of(final Integer assumedUsedCount, final LocalDate assumedDate,
            final int realUsedCount, final LocalDate today) {
        return new SimulationContext(assumedUsedCount == null ? realUsedCount : assumedUsedCount,
                assumedDate == null ? today : assumedDate);
    }
}
