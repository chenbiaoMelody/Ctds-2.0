package com.ctds.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ctds.common.logging.AuditRecorder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.server.MatcherSecurityWebFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway chain wiring and end-to-end behaviour (review 1 P1-1/P2-1 + review 4 P1-1 fixes):
 * fail-fast, chain filter composition, legal-token pass-through with injected identity headers,
 * and missing-token 401 envelope through the real filter pipeline.
 * Note: not via ReactiveWebApplicationContextRunner because @ConditionalOnWebApplication(REACTIVE)
 * does not evaluate true under that runner (Boot test-framework limitation); servlet-side wiring
 * is covered by example-service full-context integration tests.
 */
class GatewayChainWiringTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static AuthProperties enabledProperties() {
        final AuthProperties properties = new AuthProperties();
        properties.getGateway().setEnabled(true);
        properties.getJwt().setSecret(new String(SECRET, StandardCharsets.UTF_8));
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ServerHttpSecurity> providerOf(ServerHttpSecurity http) {
        final ObjectProvider<ServerHttpSecurity> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(http);
        return provider;
    }

    private SecurityWebFilterChain buildChain(final AuthProperties properties) {
        final GatewayAuthAutoConfiguration configuration = new GatewayAuthAutoConfiguration();
        return configuration.gatewayAuthSecurityWebFilterChain(providerOf(ServerHttpSecurity.http()),
                properties, configuration.jwtDecoder(properties),
                new GatewayAuthEntryPoint(mock(AuditRecorder.class), false));
    }

    @Test
    void enabledWithoutServerHttpSecurityShouldFailFast() {
        final GatewayAuthAutoConfiguration configuration = new GatewayAuthAutoConfiguration();
        final AuthProperties properties = enabledProperties();
        final JwtDecoder decoder = configuration.jwtDecoder(properties);
        final ServerAuthenticationEntryPoint entryPoint =
                new GatewayAuthEntryPoint(mock(AuditRecorder.class), false);

        assertThatThrownBy(() -> configuration.gatewayAuthSecurityWebFilterChain(
                providerOf(null), properties, decoder, entryPoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ServerHttpSecurity");
    }

    @Test
    void enabledWithServerHttpSecurityShouldWireBearerAuthAndEntryPoints() {
        final SecurityWebFilterChain chain = buildChain(enabledProperties());

        // Chain filter composition pins the wiring (review 4 P1-1): the reactive resource-server
        // installs AuthenticationWebFilter carrying the bearer converter/manager; deleting either
        // the oauth2ResourceServer or exceptionHandling wiring turns this red.
        final List<String> filterNames = ((MatcherSecurityWebFilterChain) chain).getWebFilters()
                .map(filter -> filter.getClass().getSimpleName()).collectList().block();
        assertThat(filterNames).isNotNull();
        assertThat(filterNames.toString())
                .contains("AuthenticationWebFilter")
                .contains("AuthorizationWebFilter")
                .contains("ExceptionTranslationWebFilter");
    }

    @Test
    void chainEndToEndLegalTokenShouldReachDownstreamWithInjectedIdentityHeaders() throws Exception {
        final AuthProperties properties = enabledProperties();
        final SecurityWebFilterChain chain = buildChain(properties);
        final String token = GatewayJwtTestSupport.signedToken(SECRET,
                GatewayJwtTestSupport.expiringClaims("u-9", List.of("user")));
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/greetings")
                        .header("X-Ctds-Subject", "attacker")
                        .header("Authorization", "Bearer " + token)
                        .build());
        final ServerWebExchange[] downstream = new ServerWebExchange[1];
        final WebFilterChainProxy proxy = new WebFilterChainProxy(chain);

        // Real pipeline order as driven by the container via Ordered: strip(-200) -> chain -> inject(-90) -> downstream
        new HeaderStripFilter(properties).filter(exchange, stripped -> proxy.filter(stripped, secured ->
                new ContextHeaderInjectFilter(properties).filter(secured, injected -> {
                    downstream[0] = injected;
                    return Mono.empty();
                }))).block();

        assertThat(downstream[0]).as("legal token should reach downstream").isNotNull();
        assertThat(downstream[0].getRequest().getHeaders().getFirst("X-Ctds-Subject")).isEqualTo("u-9");
        assertThat(downstream[0].getRequest().getHeaders().getFirst("X-Ctds-Roles")).isEqualTo("user");
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void chainEndToEndMissingTokenShouldAnswer401Envelope() {
        final AuthProperties properties = enabledProperties();
        final MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/greetings").build());
        final WebFilterChainProxy proxy = new WebFilterChainProxy(buildChain(properties));
        final boolean[] reachedDownstream = {false};

        new HeaderStripFilter(properties).filter(exchange, stripped -> proxy.filter(stripped, secured -> {
            reachedDownstream[0] = true;
            return Mono.empty();
        })).block();

        assertThat(reachedDownstream).containsExactly(false);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        final String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("1000C0002").contains(AuthAdvice.UNAUTHORIZED_MESSAGE);
    }

    @Test
    void jwtDecoderShouldRequireExactlyOneKeySource() {
        final GatewayAuthAutoConfiguration configuration = new GatewayAuthAutoConfiguration();

        final AuthProperties none = new AuthProperties();
        assertThatThrownBy(() -> configuration.jwtDecoder(none))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("二选一");

        final AuthProperties both = new AuthProperties();
        both.getJwt().setSecret(new String(SECRET, StandardCharsets.UTF_8));
        both.getJwt().setJwkSetUri("https://op.example.invalid/jwks.json");
        assertThatThrownBy(() -> configuration.jwtDecoder(both))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("二选一");

        final AuthProperties weak = new AuthProperties();
        weak.getJwt().setSecret("short");
        assertThatThrownBy(() -> configuration.jwtDecoder(weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }
}
