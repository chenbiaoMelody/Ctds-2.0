package com.ctds.common.guardrail.audit;

/**
 * B2 AI 调用审计记录器契约：仅元数据；实现须自行保证线程安全。
 * 实现铁律沿 ADR-005 §3-6：绝不抛错、绝不阻塞；V1.0 默认 NOOP，不落库、不写文件。
 */
@FunctionalInterface
public interface AiCallLogRecorder {

    AiCallLogRecorder NOOP = log -> {
    };

    void record(AiCallLog log);
}