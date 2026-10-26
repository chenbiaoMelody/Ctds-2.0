package com.ctds.connector.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ctds.connector.sdk.model.UsageRequest;
import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 使用控制 fail-closed 语义（hifi T9~T13 / 规格行为 5——AGENTS §4 双向用例硬约束落点）：
 * 放行 / 越界拒绝原码透传不重试 / 不可达与超时绝不放行。
 *
 * <p>判定落点 = 网关交付面 {@code GET /api/v1/data-products/{productId}}（网关
 * {@code PolicyExecutionGlobalFilter} 转发前判定，403 {@code 1008C0020} 在该端点真实可发生）。</p>
 */
class UsageControlFailClosedTest {

    private static final String BASE = "http://platform.test";

    private static final String URL = BASE + "/api/v1/data-products/601";

    private final RestClient.Builder restBuilder = RestClient.builder().baseUrl(BASE);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restBuilder).build();

    private final PlatformClient client = new PlatformClient(restBuilder, new StaticTokenProvider("tok-1"));

    private final DefaultUsageControlCapability usage =
            new DefaultUsageControlCapability(client, new DefaultDeliveryCapability(client));

    private static final UsageRequest REQUEST = new UsageRequest(601L, "CT001", "数据分析", "境内");

    private static final String PRODUCT_JSON = "{\"productId\":601,\"productName\":\"信用数据 API\","
            + "\"intro\":\"按次调用\",\"productType\":\"API接口\",\"pricingModel\":\"按次\","
            + "\"categoryCode\":\"CAT-01\",\"categoryName\":\"企业数据\",\"providerSubjectNo\":\"S001\","
            + "\"listedAt\":\"2026-10-01T12:00:00\",\"status\":\"已上架\"}";

    @Test
    void 合约范围内使用_平台放行_返回被交付资源() {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess("{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"t1\","
                        + "\"data\":" + PRODUCT_JSON + "}", MediaType.APPLICATION_JSON));
        var result = usage.executeUsage(REQUEST);
        assertThat(result.productId()).isEqualTo(601L);
        assertThat(result.resource().productName()).isEqualTo("信用数据 API");
        server.verify();
    }

    @Test
    void 越界使用_平台拒绝_原码原文透传且不重试() {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"1008C0020\",\"message\":\"使用越界，已拒绝\",\"traceId\":\"t2\"}"));
        assertThatThrownBy(() -> usage.executeUsage(REQUEST))
                .isInstanceOfSatisfying(ConnectorException.class, e -> {
                    assertThat(e.platformCode()).isEqualTo("1008C0020");
                    assertThat(e.platformMessage()).isEqualTo("使用越界，已拒绝");
                    assertThat(e.sdkErrorType()).isNull();
                });
        server.verify();
    }

    @Test
    void 合约未生效_状态门槛拒绝_原码透传() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"1008C0013\",\"message\":\"合约状态不允许该操作\",\"traceId\":\"t3\"}"));
        assertThatThrownBy(() -> usage.executeUsage(REQUEST))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.platformCode()).isEqualTo("1008C0013"));
        server.verify();
    }

    @Test
    void 被交付资源不存在_404透传() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"1007C0005\",\"message\":\"产品不存在\",\"traceId\":\"t4\"}"));
        assertThatThrownBy(() -> usage.executeUsage(REQUEST))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.platformCode()).isEqualTo("1007C0005"));
        server.verify();
    }

    @Test
    void 平台不可达_failClosed_绝无成功返回() {
        server.expect(requestTo(URL))
                .andRespond(withException(new IOException("connection refused")));
        assertThatThrownBy(() -> usage.executeUsage(REQUEST))
                .isInstanceOfSatisfying(ConnectorException.class, e -> {
                    assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.UNREACHABLE);
                    assertThat(e.platformCode()).isNull();
                });
    }

    @Test
    void 平台调用超时_中性分类TIMEOUT() {
        server.expect(requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThatThrownBy(() -> usage.executeUsage(REQUEST))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.TIMEOUT));
    }

    @Test
    void 成功响应缺失数据_按不可达failClosed() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"t5\"}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> usage.executeUsage(REQUEST))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.UNREACHABLE));
        server.verify();
    }

    @Test
    void 拒绝响应非统一结构_中性分类不吞异常() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_PLAIN)
                        .body("Forbidden"));
        assertThatThrownBy(() -> usage.executeUsage(REQUEST))
                .isInstanceOfSatisfying(ConnectorException.class, e -> {
                    assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.UNREACHABLE);
                    assertThat(e.platformCode()).isNull();
                });
        server.verify();
    }

    @Test
    void 凭据缺失_本地failFast_不发起平台调用() {
        assertThatThrownBy(() -> usage.executeUsage(new UsageRequest(601L, null, "数据分析", "境内")))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        assertThatThrownBy(() -> usage.executeUsage(new UsageRequest(601L, "含空格 CT001", null, null)))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        assertThatThrownBy(() -> usage.executeUsage(new UsageRequest(null, "CT001", null, null)))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        server.verify();
    }

    @Test
    void 令牌缺失_本地failFast不发起调用() {
        assertThatThrownBy(() -> new StaticTokenProvider(" "))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        RestClient.Builder emptyTokenBuilder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer emptyTokenServer = MockRestServiceServer.bindTo(emptyTokenBuilder).build();
        PlatformClient emptyTokenClient = new PlatformClient(emptyTokenBuilder, () -> null);
        var capability = new DefaultUsageControlCapability(emptyTokenClient,
                new DefaultDeliveryCapability(emptyTokenClient));
        assertThatThrownBy(() -> capability.executeUsage(REQUEST))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        emptyTokenServer.verify();
    }
}
