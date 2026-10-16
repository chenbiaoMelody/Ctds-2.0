package com.ctds.gateway.policy;

/**
 * 策略判定不可用（服务不可达 / 超时 / 返回非合约域业务码）：一律 fail-closed——不触达数据源、
 * 不放行、不产生计量事件，对外 503 + {@code 1009S0001}（hifi §2 / §7）。
 */
public class PolicyUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PolicyUnavailableException(final Throwable cause) {
        super("策略判定服务不可用（fail-closed）", cause);
    }
}
