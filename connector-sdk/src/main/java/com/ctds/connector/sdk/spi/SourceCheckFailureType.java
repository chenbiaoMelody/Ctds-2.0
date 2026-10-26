package com.ctds.connector.sdk.spi;

/**
 * 连通性自检失败原因（规格行为 13 规则 2）：最低覆盖三类，业务可读。
 */
public enum SourceCheckFailureType {

    /** 网络不可达。 */
    NETWORK_UNREACHABLE,

    /** 凭据无效。 */
    INVALID_CREDENTIAL,

    /** 权限不足。 */
    INSUFFICIENT_PERMISSION
}
