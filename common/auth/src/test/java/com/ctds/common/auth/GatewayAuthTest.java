package com.ctds.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ctds.common.auth.GatewayAuthException.FailReason;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** B7/B8：网关侧 JWT 校验（原因归一只进审计）、401 统一封套、身份头剥离与注入、密钥来源校验。 */
class GatewayAuthTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_SECRET = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String jwt(byte[] key, JWTClaimsSet claims) throws Exception {
        final SignedJWT signed = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        signed.sign(new MACSigner(key));
        return signed.serialize();
    }

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .subject("u-9")
                .claim("roles", List.of("user"))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    }

    private static JwtDecoder decoder(String issuer) {
        final AuthProperties properties = new AuthProperties();
        properties.getJwt().setSecret(new String(SECRET, StandardCharsets.UTF_8));
        properties.getJwt().setIssuer(issuer);
        return new GatewayAuthAutoConfiguration().jwtDecoder(properties);
    }

    // ---------- MappingJwtDecoder：校验通过与原因归类（B7/B8） ----------

    @Test
    void validTokenShouldDecode() throws Exception {
        final Jwt jwt = decoder(null).decode(jwt(SECRET, claims().build()));

        assertEquals("u-9", jwt.getSubject());
        assertEquals(List.of("user"), jwt.getClaimAsStringList("roles"));
    }

    @Test
    void expiredTokenShouldMapToExpired() throws Exception {
        final String token = jwt(SECRET, new JWTClaimsSet.Builder()
                .subject("u-9").expirationTime(Date.from(Instant.now().minusSeconds(120))).build());

        final GatewayAuthException ex = assertThrows(GatewayAuthException.class, () -> decoder(null).decode(token));
        assertEquals(FailReason.EXPIRED, ex.getReason());
    }

    @Test
    void wrongSecretShouldMapToBadSignature() throws Exception {
        final String token = jwt(OTHER_SECRET, claims().build());

        final GatewayAuthException ex = assertThrows(GatewayAuthException.class, () -> decoder(null).decode(token));
        assertEquals(FailReason.BAD_SIGNATURE, ex.getReason());
    }

    @Test
    void malformedTokenShouldMapToMalformed() {
        final GatewayAuthException ex = assertThrows(GatewayAuthException.class, () -> decoder(null).decode("abc"));
        assertEquals(FailReason.MALFORMED, ex.getReason());
    }

    @Test
    void missingExpiryShouldMapToMalformed() throws Exception {
        final String token = jwt(SECRET, new JWTClaimsSet.Builder().subject("u-9").build());

        final GatewayAuthException ex = assertThrows(GatewayAuthException.class, () -> decoder(null).decode(token));
        assertEquals(FailReason.MALFORMED, ex.getReason());
    }

    @Test
    void wrongIssuerShouldMapToBadIssuerOnlyWhenConfigured() throws Exception {
        final String token = jwt(SECRET, claims().issuer("other-space").build());

        final GatewayAuthException ex = assertThrows(GatewayAuthException.class,
                () -> decoder("city-tds").decode(token));
        assertEquals(FailReason.BAD_ISSUER, ex.getReason());
        // 未配置 issuer 时不校验该声明
        assertEquals("u-9", decoder(null).decode(token).getSubject());
    }

    @Test
    void nbfWithinSixtySecondSkewShouldPass() throws Exception {
        final String token = jwt(SECRET, claims().notBeforeTime(Date.from(Instant.now().plusSeconds(30))).build());

        assertEquals("u-9", decoder(null).decode(token).getSubject());
    }

    @Test
    void blockingToReactiveAdapterShouldPropagateReason() throws Exception {
        final JwtDecoder blocking = decoder(null);
        final String token = jwt(SECRET, new JWTClaimsSet.Builder()
                .subject("u-9").expirationTime(Date.from(Instant.now().minusSeconds(120))).build());

        assertThrows(GatewayAuthException.class,
                () -> new BlockingToReactiveJwtDecoder(blocking).decode(token).block());
    }

    // ---------- 密钥来源配置化（B8：二选一启动失败）由 GatewayChainWiringTest.jwtDecoderShouldRequireExactlyOneKeySource 覆盖 ----------

    // ---------- 401 统一出口（B7：封套同服务侧口径 + 审计只含原因） ----------

    private MockServerWebExchange runEntryPoint(AuthenticationException ex, List<AuditEvent> events) {
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/v1/greetings").build());
        new GatewayAuthEntryPoint(events::add, true).commence(exchange, ex).block();
        return exchange;
    }

    @Test
    void unauthorizedShouldWrite401EnvelopeWithReasonAudit() throws Exception {
        final List<AuditEvent> events = new java.util.ArrayList<>();
        final MockServerWebExchange exchange = runEntryPoint(
                new GatewayAuthException(FailReason.EXPIRED), events);

        assertEquals(401, exchange.getResponse().getStatusCode().value());
        assertTrue(exchange.getResponse().getHeaders().getContentType().includes(
                org.springframework.http.MediaType.APPLICATION_JSON));
        final JsonNode body = MAPPER.readTree(exchange.getResponse().getBodyAsString().block());
        assertEquals("1000C0002", body.get("code").asText());
        assertEquals("认证失败或身份已失效", body.get("message").asText());
        assertEquals("-", body.get("traceId").asText());

        assertEquals(1, events.size());
        final AuditEvent event = events.get(0);
        assertEquals("auth.verify", event.action());
        assertEquals(AuditOutcome.FAILURE, event.outcome());
        assertEquals("EXPIRED", event.detail().get("reason"));
        assertEquals("anonymous", event.actor());
    }

    @Test
    void missingHeaderShouldMapToMissingReason() throws Exception {
        final List<AuditEvent> events = new java.util.ArrayList<>();
        runEntryPoint(new InsufficientAuthenticationException("Full authentication required"), events);

        assertEquals("MISSING", events.get(0).detail().get("reason"));
    }

    @Test
    void auditDisabledShouldStillAnswer401() throws Exception {
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/x").build());
        new GatewayAuthEntryPoint(event -> { }, false)
                .commence(exchange, new GatewayAuthException(FailReason.MALFORMED)).block();

        assertEquals(401, exchange.getResponse().getStatusCode().value());
    }

    // ---------- 信任边界：剥离与注入（B7） ----------

    @Test
    void stripFilterShouldDropForgedContextHeaders() {
        final AuthProperties properties = new AuthProperties();
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/x")
                        .header("X-Ctds-Subject", "attacker").header("X-Ctds-Roles", "admin").build());
        final ServerWebExchange[] downstream = new ServerWebExchange[1];

        new HeaderStripFilter(properties).filter(exchange,
                ex -> { downstream[0] = ex; return Mono.empty(); }).block();

        assertFalse(downstream[0].getRequest().getHeaders().containsKey("X-Ctds-Subject"));
        assertFalse(downstream[0].getRequest().getHeaders().containsKey("X-Ctds-Roles"));
    }

    @Test
    void injectFilterShouldReplaceForgedHeadersWithJwtClaims() {
        final AuthProperties properties = new AuthProperties();
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/x")
                        .header("X-Ctds-Subject", "attacker").build());
        final Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "HS256"), Map.of("sub", "u-9", "roles", List.of("user", "admin")));
        final String[] holder = new String[2];

        new ContextHeaderInjectFilter(properties).filter(exchange, ex -> {
                    holder[0] = ex.getRequest().getHeaders().getFirst("X-Ctds-Subject");
                    holder[1] = ex.getRequest().getHeaders().getFirst("X-Ctds-Roles");
                    return Mono.empty();
                }).contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        new JwtAuthenticationToken(jwt)))
                .block();

        assertEquals("u-9", holder[0], "伪造头必须被权威值替换");
        assertEquals("user,admin", holder[1]);
    }

    @Test
    void injectFilterShouldAcceptCommaStringRolesClaim() {
        final AuthProperties properties = new AuthProperties();
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/x").build());
        final Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "HS256"), Map.of("sub", "u-9", "roles", "user, auditor"));
        final String[] holder = new String[1];

        new ContextHeaderInjectFilter(properties).filter(exchange, ex -> {
                    holder[0] = ex.getRequest().getHeaders().getFirst("X-Ctds-Roles");
                    return Mono.empty();
                }).contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        new JwtAuthenticationToken(jwt)))
                .block();

        assertEquals("user,auditor", holder[0]);
    }

    @Test
    void injectFilterShouldSkipNonStringRoleEntries() {
        // 评审②P3-2：IdP 签出数字等非字符串声明项时跳过（不得 ClassCastException 打崩认证路径）
        final AuthProperties properties = new AuthProperties();
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/x").build());
        final Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "HS256"), Map.of("sub", "u-9", "roles", List.of(123, "user")));
        final String[] holder = new String[1];

        new ContextHeaderInjectFilter(properties).filter(exchange, ex -> {
                    holder[0] = ex.getRequest().getHeaders().getFirst("X-Ctds-Roles");
                    return Mono.empty();
                }).contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        new JwtAuthenticationToken(jwt)))
                .block();

        assertEquals("user", holder[0]);
    }
}
