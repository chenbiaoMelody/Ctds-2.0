package com.ctds.connector.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ctds.connector.sdk.model.FileDeliveryRequest;
import com.ctds.connector.sdk.model.ProductAccessRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 交付门面契约（hifi T8 / 任务卡 §七 P2-1 / P2-8）：API 型经网关交付面（凭据头 RFC 3986
 * 百分号编码，与网关 {@code UriUtils.decode} 配对）；文件型幂等键必填且原值发送。
 */
class DeliveryCapabilityTest {

    private static final String BASE = "http://platform.test";

    /** 「数据分析」UTF-8 百分号编码（网关解码后为业务原值）。 */
    private static final String PURPOSE_ENCODED = "%E6%95%B0%E6%8D%AE%E5%88%86%E6%9E%90";

    /** 「境内」UTF-8 百分号编码。 */
    private static final String TERRITORY_ENCODED = "%E5%A2%83%E5%86%85";

    private final RestClient.Builder restBuilder = RestClient.builder().baseUrl(BASE);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restBuilder).build();

    private final PlatformClient client = new PlatformClient(restBuilder, new StaticTokenProvider("tok-1"));

    private final DefaultDeliveryCapability delivery = new DefaultDeliveryCapability(client);

    private static final String PRODUCT_JSON = "{\"productId\":601,\"productName\":\"信用数据 API\","
            + "\"intro\":\"按次调用\",\"productType\":\"API接口\",\"pricingModel\":\"按次\","
            + "\"categoryCode\":\"CAT-01\",\"categoryName\":\"企业数据\",\"providerSubjectNo\":\"S001\","
            + "\"listedAt\":\"2026-10-01T12:00:00\",\"status\":\"已上架\"}";

    private static final String DELIVERY_JSON = "{\"deliveryNo\":\"DL20261001001\","
            + "\"contractNo\":\"CT001\",\"productId\":601,\"productName\":\"信用数据 API\","
            + "\"deliveryType\":\"FILE\",\"requesterNo\":\"S001\",\"providerNo\":\"S002\","
            + "\"fileId\":\"F-001\",\"fileName\":\"数据包.zip\",\"fileSize\":1024,"
            + "\"packageId\":\"PKG-001\",\"checksum\":\"sm3:abc\",\"result\":\"SUCCESS\","
            + "\"createdAt\":\"2026-10-01T14:00:00\"}";

    @Test
    void API型交付_经网关交付面_凭据头按RFC3986编码() {
        server.expect(requestTo(BASE + "/api/v1/data-products/601"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andExpect(header("X-Ctds-Contract-No", "CT001"))
                .andExpect(header("X-Ctds-Purpose", PURPOSE_ENCODED))
                .andExpect(header("X-Ctds-Territory", TERRITORY_ENCODED))
                .andRespond(withSuccess("{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"t1\","
                        + "\"data\":" + PRODUCT_JSON + "}", MediaType.APPLICATION_JSON));
        var view = delivery.requestApiDelivery(
                new ProductAccessRequest(601L, "CT001", "数据分析", "境内"));
        assertThat(view.productId()).isEqualTo(601L);
        assertThat(view.productName()).isEqualTo("信用数据 API");
        server.verify();
    }

    @Test
    void API型交付_越界拒绝_原码透传且不重试() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v1/data-products/601"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"1008C0020\",\"message\":\"使用越界，已拒绝\",\"traceId\":\"t2\"}"));
        assertThatThrownBy(() -> delivery.requestApiDelivery(
                new ProductAccessRequest(601L, "CT001", "数据分析", "境内")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> {
                    assertThat(e.platformCode()).isEqualTo("1008C0020");
                    assertThat(e.platformMessage()).isEqualTo("使用越界，已拒绝");
                    assertThat(e.sdkErrorType()).isNull();
                });
        server.verify();
    }

    @Test
    void API型交付_策略要素未声明_不携带对应凭据头() {
        server.expect(requestTo(BASE + "/api/v1/data-products/601"))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getFirst("X-Ctds-Purpose")).isNull();
                    assertThat(request.getHeaders().getFirst("X-Ctds-Territory")).isNull();
                })
                .andRespond(withSuccess("{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"t1\","
                        + "\"data\":" + PRODUCT_JSON + "}", MediaType.APPLICATION_JSON));
        delivery.requestApiDelivery(new ProductAccessRequest(601L, "CT001", null, " "));
        server.verify();
    }

    @Test
    void 文件型交付_幂等键必填_本地failFast不发起调用() {
        assertThatThrownBy(() -> delivery.requestFileDelivery(
                new FileDeliveryRequest("CT001", "F-001", "数据分析", "境内", null)))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        assertThatThrownBy(() -> delivery.requestFileDelivery(
                new FileDeliveryRequest("CT001", "F-001", "数据分析", "境内", " ")))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        assertThatThrownBy(() -> delivery.requestFileDelivery(
                new FileDeliveryRequest("CT001", null, "数据分析", "境内", "idem-42")))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        server.verify();
    }

    @Test
    void 文件型交付_请求体与幂等头按平台契约() {
        server.expect(requestTo(BASE + "/api/v1/deliveries"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Idempotency-Key", "idem-42"))
                .andExpect(header("X-Ctds-Contract-No", "CT001"))
                .andExpect(header("X-Ctds-Purpose", PURPOSE_ENCODED))
                .andExpect(jsonPath("$.contractNo").value("CT001"))
                .andExpect(jsonPath("$.deliveryType").value("FILE"))
                .andExpect(jsonPath("$.fileId").value("F-001"))
                .andExpect(jsonPath("$.purpose").value("数据分析"))
                .andExpect(jsonPath("$.territory").value("境内"))
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andRespond(withSuccess("{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"t1\","
                        + "\"data\":" + DELIVERY_JSON + "}", MediaType.APPLICATION_JSON));
        var view = delivery.requestFileDelivery(
                new FileDeliveryRequest("CT001", "F-001", "数据分析", "境内", "idem-42"));
        assertThat(view.deliveryNo()).isEqualTo("DL20261001001");
        assertThat(view.deliveryType()).isEqualTo("FILE");
        assertThat(view.result()).isEqualTo("SUCCESS");
        server.verify();
    }

    @Test
    void 文件型交付_合约状态门槛拒绝_原码透传() {
        server.expect(requestTo(BASE + "/api/v1/deliveries"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"1008C0013\",\"message\":\"合约状态不允许该操作\",\"traceId\":\"t3\"}"));
        assertThatThrownBy(() -> delivery.requestFileDelivery(
                new FileDeliveryRequest("CT001", "F-001", "数据分析", "境内", "idem-42")))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.platformCode()).isEqualTo("1008C0013"));
        server.verify();
    }

    @Test
    void 查交付详情_平台DeliveryView字段() {
        server.expect(requestTo(BASE + "/api/v1/deliveries/DL20261001001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"t1\","
                        + "\"data\":" + DELIVERY_JSON + "}", MediaType.APPLICATION_JSON));
        var view = delivery.getDelivery("DL20261001001");
        assertThat(view.contractNo()).isEqualTo("CT001");
        assertThat(view.result()).isEqualTo("SUCCESS");
        server.verify();
    }

    @Test
    void 凭据合约编号非法_本地failFast不发起调用() {
        assertThatThrownBy(() -> delivery.requestApiDelivery(
                new ProductAccessRequest(601L, "含空格 CT001", null, null)))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        assertThatThrownBy(() -> delivery.requestApiDelivery(new ProductAccessRequest(null, "CT001", null, null)))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        server.verify();
    }
}
