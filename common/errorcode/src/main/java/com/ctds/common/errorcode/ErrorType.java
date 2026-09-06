package com.ctds.common.errorcode;

import org.springframework.http.HttpStatus;

/**
 * 错误类型（ADR-005 §3.3）：C=客户端错误，B=业务规则拒绝，S=系统内部错误。
 * HTTP 状态映射：默认 C/B → 400，S → 500；特定语义错误码可在处理器映射更精确状态（如 404/405）。
 * 对外不暴露内部实现（章程 4.3）。
 */
public enum ErrorType {
    CLIENT('C', HttpStatus.BAD_REQUEST),
    BUSINESS('B', HttpStatus.BAD_REQUEST),
    SYSTEM('S', HttpStatus.INTERNAL_SERVER_ERROR);

    private final char marker;
    private final HttpStatus httpStatus;

    ErrorType(final char marker, final HttpStatus httpStatus) {
        this.marker = marker;
        this.httpStatus = httpStatus;
    }

    public char marker() {
        return marker;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public static ErrorType fromChar(final char marker) {
        for (final ErrorType type : values()) {
            if (type.marker == marker) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown error type marker: " + marker);
    }
}
