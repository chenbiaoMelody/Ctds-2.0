package com.ctds.delivery.domain;

import org.springframework.http.HttpStatus;

/**
 * 合约域业务拒绝原码透传（hifi §4.3）：code/message/status 均来自合约域响应，
 * 交付域不改写、不新增第二套判定。
 */
public class ContractPassThroughException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public ContractPassThroughException(final String code, final String message, final HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
