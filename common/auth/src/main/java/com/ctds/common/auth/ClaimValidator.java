package com.ctds.common.auth;

import com.ctds.common.auth.GatewayAuthException.FailReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * 声明校验（exp/nbf + 可选 iss）：时间校验允许 60 秒时钟偏移（采纳 Nimbus/Spring 默认口径），
 * 失败抛带内部原因的 {@link GatewayAuthException}（对外一律统一 401 文案，不区分原因）。exp 缺失视为令牌不合规。
 */
class ClaimValidator implements OAuth2TokenValidator<Jwt> {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final Clock clock;
    private final String expectedIssuer;

    ClaimValidator(final Clock clock, final String expectedIssuer) {
        this.clock = clock;
        this.expectedIssuer = expectedIssuer;
    }

    @Override
    public OAuth2TokenValidatorResult validate(final Jwt jwt) {
        final Instant now = clock.instant();
        final Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null) {
            throw new GatewayAuthException(FailReason.MALFORMED);
        }
        if (now.isAfter(expiresAt.plus(CLOCK_SKEW))) {
            throw new GatewayAuthException(FailReason.EXPIRED);
        }
        final Instant notBefore = jwt.getNotBefore();
        if (notBefore != null && now.isBefore(notBefore.minus(CLOCK_SKEW))) {
            throw new GatewayAuthException(FailReason.EXPIRED);
        }
        if (expectedIssuer != null && !expectedIssuer.isBlank()
                && !expectedIssuer.equals(jwt.getClaimAsString(JwtClaimNames.ISS))) {
            throw new GatewayAuthException(FailReason.BAD_ISSUER);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
