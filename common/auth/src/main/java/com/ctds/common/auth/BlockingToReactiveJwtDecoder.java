package com.ctds.common.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * 阻塞校验器 → 反应式适配（SecurityWebFilterChain 的 jwt 配置要求 ReactiveJwtDecoder，
 * 校验内核只写一份 JwtDecoder，SM2 扩展点覆盖的是后者）。解码异常以 Mono.error 信号传播。
 */
class BlockingToReactiveJwtDecoder implements ReactiveJwtDecoder {

    private final JwtDecoder delegate;

    BlockingToReactiveJwtDecoder(final JwtDecoder delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<Jwt> decode(final String token) {
        // fromCallable 把解码抛出的异常转为 Mono error 信号（认证失败处理链按 error 消费）
        return Mono.fromCallable(() -> delegate.decode(token));
    }
}
