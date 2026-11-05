package com.ctds.evidence.domain;

/**
 * 存证链不可达（适配器异常 / 演示期关闭网关 / 账本写入竞争耗尽）。
 * 调用方必须把它映射为 FAILED 或 503 1012S0001，禁止静默吞掉（规格 B4-1）。
 */
public class EvidenceChainUnavailableException extends RuntimeException {

    public EvidenceChainUnavailableException(final String message) {
        super(message);
    }

    public EvidenceChainUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
