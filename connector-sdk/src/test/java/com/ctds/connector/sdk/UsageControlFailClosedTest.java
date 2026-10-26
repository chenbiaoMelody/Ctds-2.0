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
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 使用控制 fail-closed 三态（hifi T9~T13 / 规格行为 5——AGENTS §4 双向用例硬约束落点）：
 * 放行 / 越界拒绝原码透传不重试 / 不可达与超时绝不放行。
 */
class UsageControlFailClosedTest {

    private static final String BASE = "http://platform.test";

    private final RestClient.Builder restBuilder = RestClient.builder().baseUrl(BASE);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restBuilder).build();

    private final DefaultUsageControlCapability usage = new DefaultUsageControlCapability(
            new PlatformClient(restBuilder, new StaticTokenProvider("tok-1")));

    private static final UsageRequest REQUEST = new UsageRequest("CT001", "数据分析", "境内", null);

    @Test
    void 合约范围内使用_平台放行_返回成功结果() {
        server.expect(requestTo(BASE + "/api/v1/deliveries"))
                .andRespond(withSuccess(
                        "{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"t1\",\"data\":"
                                + "{\"deliveryNo\":\"DL001\",\"contractNo\":\"CT001\",\"deliveryType\":\"API\","
                                + "\"status\":\"SUCCEEDED\",\"result\":\"ALLOWED\"}}",
                        MediaType.APPLICATION_JSON));
        var result = usage.executeUsage(REQUEST);
        assertThat(result.deliveryNo()).isEqualTo("DL001");
        assertThat(result.result()).isEqualTo("ALLOWED");
        server.verify();
    }

    @Test
    void 越界使用_平台拒绝_原码原文透传且不重试() {
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                requestTo(BASE + "/api/v1/deliveries"))
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
    void 未生效合约发起交付_状态门槛拒绝_原码透传() {
        server.expect(requestTo(BASE + "/api/v1/deliveries"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"1008C0013\",\"message\":\"合约状态不允许该操作\",\"traceId\":\"t3\"}"));
        assertThatThrownBy(() -> usage.executeUsage(REQUEST))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.platformCode()).isEqualTo("1008C0013"));
        server.verify();
    }

    @Test
    void 平台不可达_failClosed_绝无成功返回() {
        server.expect(requestTo(BASE + "/api/v1/deliveries"))
                .andRespond(withException(new IOException("connection refused")));
        assertThatThrownBy(() -> usage.executeUsage(REQUEST))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.UNREACHABLE));
    }

    @Test
    void 平台调用超时_中性分类TIMEOUT() {
        server.expect(requestTo(BASE + "/api/v1/deliveries"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThatThrownBy(() -> usage.executeUsage(REQUEST))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.TIMEOUT));
    }

    @Test
    void 凭据三件缺失_CONFIG分类_不发起平台调用() {
        assertThatThrownBy(() -> usage.executeUsage(new UsageRequest(null, "用途", "境内", null)))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        assertThatThrownBy(() -> usage.executeUsage(new UsageRequest("CT001", " ", "境内", null)))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        assertThatThrownBy(() -> usage.executeUsage(new UsageRequest("CT001", "用途", null, null)))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
    }
}
