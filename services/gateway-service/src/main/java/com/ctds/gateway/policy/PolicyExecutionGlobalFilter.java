package com.ctds.gateway.policy;

import com.ctds.gateway.config.GatewayFilterOrders;
import com.ctds.gateway.delivery.DeliveryAttributes;
import com.ctds.gateway.error.GatewayErrorCodes;
import com.ctds.gateway.error.GatewayErrorWriter;
import com.ctds.gateway.identity.IdentityHeaderResolver;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 策略执行点（hifi §2 / §4，链路第四道——转发之前）：调用合约域判定（不缓存），
 * <ul>
 *   <li><b>越界</b>（超次数 / 超期 / 用途不符 / 域外 / 再分发）→ 原码透传（1008C0020→403 /
 *       1008C0013→409 / 1008C0012→404 / 1008C0008→400），<b>不触达数据源、不产生计量事件</b>；</li>
 *   <li><b>发起人唯一</b>：判定响应携带合约需求方（applicantNo），发起人 ≠ 需求方 → 403 +
 *       {@code 1009C0003}（提供方 / 运营方不可发起交付）；</li>
 *   <li><b>判定不可达 / 超时</b> → 503 + {@code 1009S0001}（fail-closed，不放行）；</li>
 *   <li>放行 → 置 {@link DeliveryAttributes#POLICY_ALLOWED} 并交给计量埋点与转发。</li>
 * </ul>
 *
 * <p>实现期已知次序边界（登记观察项，见交付说明）：合约域 {@code check} 为"放行即计数"，
 * 故"发起人非需求方"这一交付域规则的拒绝发生在计数之后（hifi §2 固定次序 = 判定 → 发起人校验）。
 * 本卡按已确认设计实现，未擅自调整次序；如需前置该规则须走变更流程。</p>
 */
@Component
public class PolicyExecutionGlobalFilter implements GlobalFilter, Ordered {

    private final PolicyExecutionClient policyClient;
    private final GatewayErrorWriter errorWriter;
    private final IdentityHeaderResolver identityHeaders;

    public PolicyExecutionGlobalFilter(final PolicyExecutionClient policyClient,
            final GatewayErrorWriter errorWriter, final IdentityHeaderResolver identityHeaders) {
        this.policyClient = policyClient;
        this.errorWriter = errorWriter;
        this.identityHeaders = identityHeaders;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        final String contractNo = exchange.getAttribute(DeliveryAttributes.CONTRACT_NO);
        if (contractNo == null) {
            // 防御分支：凭据过滤器恒先执行；缺属性即 fail-closed（绝不静默放行）
            return errorWriter.writeDenied(exchange, HttpStatus.BAD_REQUEST,
                    GatewayErrorCodes.DELIVERY_CREDENTIAL_INVALID,
                    GatewayErrorCodes.DELIVERY_CREDENTIAL_INVALID_MESSAGE);
        }
        final String requesterNo = identityHeaders.resolveSubject(exchange);
        final UsageCheckCommand command = new UsageCheckCommand(contractNo, requesterNo,
                UsageCheckCommand.ACTION_USE, exchange.getAttribute(DeliveryAttributes.PURPOSE),
                exchange.getAttribute(DeliveryAttributes.TERRITORY));
        return policyClient.check(command)
                .flatMap(decision -> decide(exchange, chain, requesterNo, decision))
                .onErrorResume(PolicyUnavailableException.class, exception -> errorWriter.writeDenied(exchange,
                        HttpStatus.SERVICE_UNAVAILABLE, GatewayErrorCodes.POLICY_SERVICE_UNAVAILABLE,
                        GatewayErrorCodes.POLICY_SERVICE_UNAVAILABLE_MESSAGE));
    }

    private Mono<Void> decide(final ServerWebExchange exchange, final GatewayFilterChain chain,
            final String requesterNo, final PolicyDecision decision) {
        if (!decision.allowed()) {
            return errorWriter.writePassthrough(exchange, decision.httpStatus(), decision.code(),
                    decision.message());
        }
        if (decision.applicantNo() == null || !decision.applicantNo().equals(requesterNo)) {
            return errorWriter.writeDenied(exchange, HttpStatus.FORBIDDEN,
                    GatewayErrorCodes.REQUESTER_NOT_ALLOWED,
                    GatewayErrorCodes.REQUESTER_NOT_ALLOWED_MESSAGE);
        }
        exchange.getAttributes().put(DeliveryAttributes.POLICY_ALLOWED, Boolean.TRUE);
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrders.POLICY_EXECUTION;
    }
}
