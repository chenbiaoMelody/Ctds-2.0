package com.ctds.std;

/**
 * 标准能力域状态（WBS 2.4.8）：回答"这个标准域今天开了没有"的唯一探活返回值。
 *
 * @param domain      能力域
 * @param implemented 是否已开放（骨架期三域恒为 false；启用 = 后续工作包交付新实现，非配置动作）
 * @param message     业务可读状态说明（服务端常量，禁止拼接用户输入）
 */
public record StdDomainStatus(StdDomain domain, boolean implemented, String message) {

    public StdDomainStatus {
        if (domain == null) {
            throw new IllegalArgumentException("domain must not be null");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
