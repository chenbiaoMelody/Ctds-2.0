package com.ctds.connector.sdk.adapter.database;

import java.util.Optional;

/**
 * 数据库凭据形态（hifi §7.3）：{@code 用户名:密码}，按<b>首个</b>冒号切分（密码可含冒号）；
 * 缺冒号或用户名为空 = 形态非法（适配器记为"凭据无效"业务结论，不抛 5xx）。
 *
 * <p>{@link #toString()} 只显用户名，密码永不回显（防日志 / 异常二次泄露）。</p>
 */
public record DatabaseCredential(String username, String password) {

    private static final String MASK = "***";

    /** 解析凭据明文（形态非法返回空）。 */
    public static Optional<DatabaseCredential> parse(final String plaintext) {
        if (plaintext == null) {
            return Optional.empty();
        }
        final int separator = plaintext.indexOf(':');
        if (separator <= 0) {
            return Optional.empty();
        }
        final String username = plaintext.substring(0, separator);
        if (username.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new DatabaseCredential(username, plaintext.substring(separator + 1)));
    }

    @Override
    public String toString() {
        return "DatabaseCredential[username=" + username + ", password=" + MASK + "]";
    }
}
