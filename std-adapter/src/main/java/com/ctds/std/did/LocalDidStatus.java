package com.ctds.std.did;

/**
 * 本空间 DID 状态视图（业务语言，供模拟对端回放出向结论）：
 * registered = 是否已登记；effective = 是否处于"有效"态（已吊销、待签发均非有效）。
 */
public record LocalDidStatus(boolean registered, boolean effective) {
}
