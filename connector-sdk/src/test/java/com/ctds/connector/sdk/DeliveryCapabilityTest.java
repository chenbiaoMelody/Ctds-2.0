package com.ctds.connector.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ctds.connector.sdk.model.DeliveryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 交付门面幂等键契约（hifi T8）：显式幂等键复用；缺省自动生成（非空）。
 */
class DeliveryCapabilityTest {

    private static final String BASE = "http://platform.test";

    private final RestClient.Builder restBuilder = RestClient.builder().baseUrl(BASE);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restBuilder).build();

    private final PlatformClient client = new PlatformClient(restBuilder, new StaticTokenProvider("tok-1"));

    private static final String OK_BODY = "{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"t1\","
            + "\"data\":{\"deliveryNo\":\"DL001\",\"contractNo\":\"CT001\",\"deliveryType\":\"API\","
            + "\"status\":\"SUCCEEDED\",\"result\":\"ALLOWED\"}}";

    @Test
    void 显式幂等键_同请求复用() {
        server.expect(requestTo(BASE + "/api/v1/deliveries"))
                .andExpect(header("X-Idempotency-Key", "idem-42"))
                .andExpect(header("X-Ctds-Contract-No", "CT001"))
                .andExpect(header("X-Ctds-Purpose", "数据分析"))
                .andExpect(header("X-Ctds-Territory", "境内"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));
        var view = new DefaultDeliveryCapability(client)
                .requestDelivery(new DeliveryRequest("CT001", "API", null, "数据分析", "境内", "idem-42"));
        assertThat(view.deliveryNo()).isEqualTo("DL001");
        server.verify();
    }

    @Test
    void 幂等键缺省_自动生成非空() {
        server.expect(requestTo(BASE + "/api/v1/deliveries"))
                .andExpect(request -> assertThat(
                        request.getHeaders().getFirst("X-Idempotency-Key")).isNotBlank())
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));
        var view = new DefaultDeliveryCapability(client)
                .requestDelivery(DeliveryRequest.api("CT001", "数据分析", "境内"));
        assertThat(view.status()).isEqualTo("SUCCEEDED");
        server.verify();
    }
}
