package com.ctds.delivery.domain;

/**
 * 连接器内部面调用失败（WBS-3.6.8 W3）：占用 / 读取 / 释放 / 属主核对的不可用与拒绝统一收敛。
 * {@code reason} 为业务可读摘要（服务端常量或内部读取返回的业务摘要归类），
 * <b>不含内部码、内部实现与源信息</b>（红线：对外不透传 1010 内部码）。
 */
public class ConnectorInternalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    public ConnectorInternalException(final String reason, final Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    public ConnectorInternalException(final String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
