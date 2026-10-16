package com.ctds.gateway.policy;

import com.ctds.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 策略执行点接入（hifi §4）：经合约域<b>内部判定端点</b>消费既有 {@code check}（判定语义唯一，
 * 零第二语义；不复用 3.4.6 演示期 {@code usage-executions}）。出站注入服务身份头
 * （{@code X-Ctds-Subject: gateway-service} / {@code X-Ctds-Roles: gateway-internal}，名称可配置）。
 *
 * <p>消费口径（ADR-020 §2.8）：判定<b>不缓存</b>——每次交付调用独立判定，"放行即计数"由合约域
 * 单点承担；判定不可达 / 超时（默认 2s 可配）→ {@link PolicyUnavailableException}（fail-closed）。</p>
 */
@Component
public class PolicyExecutionClient {

    /** 合约域业务码前缀（C 型）：该前缀的码值原样透传；其余（S 型 / 非合约域）一律 fail-closed。 */
    private static final String CONTRACT_BIZ_CODE_PREFIX = "1008C";
    private static final String SUCCESS_CODE = "0";

    private static final Logger log = LoggerFactory.getLogger(PolicyExecutionClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final GatewayProperties properties;

    public PolicyExecutionClient(final WebClient policyWebClient,
            final GatewayProperties properties) {
        this.webClient = policyWebClient;
        this.properties = properties;
    }

    /** 转发前判定（每次调用独立发起，不做任何缓存）。 */
    public Mono<PolicyDecision> check(final UsageCheckCommand command) {
        return webClient.post()
                .uri(properties.getPolicy().getPath())
                .header(properties.getPolicy().getSubjectHeader(), properties.getPolicy().getSubject())
                .header(properties.getPolicy().getRolesHeader(), properties.getPolicy().getRoles())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(command)
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> toDecision(response.statusCode(), body)))
                .timeout(properties.getPolicy().getTimeout())
                .onErrorMap(exception -> !(exception instanceof PolicyUnavailableException),
                        exception -> {
                            log.warn("策略判定调用失败（fail-closed）: {}", exception.toString());
                            return new PolicyUnavailableException(exception);
                        });
    }

    private PolicyDecision toDecision(final HttpStatusCode status, final String body) {
        final JsonNode root = readTree(body);
        final String code = root.path("code").asText("");
        if (SUCCESS_CODE.equals(code)) {
            final JsonNode contract = root.path("data").path("contract");
            return PolicyDecision.allowed(textOrNull(contract.path("applicantNo")),
                    textOrNull(contract.path("providerNo")));
        }
        if (code.startsWith(CONTRACT_BIZ_CODE_PREFIX)) {
            return PolicyDecision.denied(code, root.path("message").asText(""), status.value());
        }
        // 非合约域业务码（含 S 型依赖不可用）与未知形态：判定结论不可信 → fail-closed，
        // 不把"未能判定"当作"已放行"（hifi §2 / ADR-019 判定语义唯一）。
        throw new PolicyUnavailableException(
                new IllegalStateException("判定响应不可信: httpStatus=" + status.value() + ", code=" + code));
    }

    private static JsonNode readTree(final String body) {
        try {
            return MAPPER.readTree(body);
        } catch (final Exception ex) {
            throw new PolicyUnavailableException(ex);
        }
    }

    private static String textOrNull(final JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
