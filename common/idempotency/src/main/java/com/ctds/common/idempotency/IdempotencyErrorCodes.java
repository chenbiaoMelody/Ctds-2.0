package com.ctds.common.idempotency;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 幂等与分布式锁组件码表（模块位 1002，契约见 ADR-007 与 docs/designs/WBS-2.4.7-hifi.md）。
 * 对外文案为服务端常量，禁止拼接用户输入（防响应回显，红线：不暴露内部实现）。
 */
public final class IdempotencyErrorCodes {

    /** 幂等键已占用且暂无结果（业务仍在执行）。→ 400 */
    public static final ErrorCode IDEMPOTENCY_IN_PROGRESS = ErrorCode.of("1002C0001");
    /** 锁等待超时。→ 400 */
    public static final ErrorCode LOCK_ACQUIRE_TIMEOUT = ErrorCode.of("1002C0002");
    /** 幂等存储不可用（Redis 连接异常等；fail-closed：宁可拒绝不执行业务，防重复执行）。→ 500 */
    public static final ErrorCode IDEMPOTENCY_STORE_UNAVAILABLE = ErrorCode.of("1002S0001");
    /** 锁服务不可用（fail-closed：宁可拒绝不放行，防无锁并发破坏业务）。→ 500 */
    public static final ErrorCode LOCK_SERVICE_UNAVAILABLE = ErrorCode.of("1002S0002");

    /** 对外文案（C 码出站展示；S 码出站由 GlobalExceptionHandler 统一替换为"系统繁忙"）。 */
    public static final String IDEMPOTENCY_IN_PROGRESS_MESSAGE = "请求处理中，请稍后重试";
    public static final String LOCK_ACQUIRE_TIMEOUT_MESSAGE = "操作繁忙，请稍后重试";

    private IdempotencyErrorCodes() {
    }
}
