package com.ctds.gateway.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 测试令牌签发（HS256，测试期固定密钥，仅本机测试使用；沿 common-auth {@code GatewayJwtTestSupport}
 * 同款形态——该夹具包可见性为包内，网关模块无法复用，故按最小形态自建）。
 */
public final class TestTokens {

    /** 测试期 HS256 密钥（仅测试夹具值，非真实密钥；不入业务配置、不落日志）。 */
    public static final String TEST_SECRET = "test-only-gateway-hs256-secret-key-0123456789";

    private TestTokens() {
    }

    /** 有效令牌（sub + roles 数组）。 */
    public static String valid(final String subject, final List<String> roles) {
        return signed(subject, roles, Instant.now().plusSeconds(300));
    }

    /** 角色声明为混合类型（含非字符串项 → 应被跳过，不注入角色头）。 */
    public static String validWithMixedRoles(final String subject) {
        final JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("roles", List.of(123, "provider"))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        return sign(claims);
    }

    /** 过期令牌（边界：401 + 1000C0002）。 */
    public static String expired(final String subject) {
        return signed(subject, List.of("provider"), Instant.now().minusSeconds(60));
    }

    /** 错误密钥签发的令牌（边界：签名校验失败）。 */
    public static String wrongSignature(final String subject) {
        final JWTClaimsSet claims = claims(subject, List.of("provider"), Instant.now().plusSeconds(300));
        try {
            final SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
            jwt.sign(new MACSigner("another-test-only-secret-key-0123456789ab"
                    .getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String signed(final String subject, final List<String> roles,
            final Instant expiration) {
        return sign(claims(subject, roles, expiration));
    }

    private static JWTClaimsSet claims(final String subject, final List<String> roles,
            final Instant expiration) {
        return new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("roles", roles)
                .expirationTime(Date.from(expiration))
                .build();
    }

    private static String sign(final JWTClaimsSet claims) {
        try {
            final SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
            jwt.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
