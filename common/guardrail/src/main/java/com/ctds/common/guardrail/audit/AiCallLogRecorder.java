package com.ctds.common.guardrail.audit;

/**
 * B2 AI 调用审计记录器契约：仅元数据；实现须自行保证线程安全。
 * 实现铁律沿 ADR-005 §3-6：绝不抛错、绝不阻塞；V1.0 默认 NOOP，不落库、不写文件。
 * V1.5 落库实现须使用参数化 SQL，按 DDL 列宽校验/截断，并暴露丢弃/失败计数
 * （hifi §9 E-03）；异常语义以 hifi §6 / E-04 为准。
 */
@FunctionalInterface
public interface AiCallLogRecorder {

    AiCallLogRecorder NOOP = log -> {
    };

    void record(AiCallLog log);
}