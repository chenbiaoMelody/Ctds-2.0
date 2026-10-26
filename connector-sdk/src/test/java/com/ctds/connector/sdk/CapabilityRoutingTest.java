package com.ctds.connector.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ctds.connector.sdk.model.CreateContractCommand;
import com.ctds.connector.sdk.model.CreateProductCommand;
import com.ctds.connector.sdk.model.PageResult;
import com.ctds.connector.sdk.model.RegisterDatasetCommand;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 六门面路由契约（hifi T2~T7 / §5 端点映射表）：路径 / Bearer 头 / 载荷字段 / 视图解析，
 * mock 响应体字段与枚举名<b>逐项取自平台真实 View / DTO</b>（零自造形状）。
 */
class CapabilityRoutingTest {

    private static final String BASE = "http://platform.test";

    private final RestClient.Builder restBuilder = RestClient.builder().baseUrl(BASE);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restBuilder).build();

    private final PlatformClient client = new PlatformClient(restBuilder, new StaticTokenProvider("tok-1"));

    private String ok(String dataJson) {
        return "{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"t1\",\"data\":" + dataJson + "}";
    }

    @Test
    void 身份腿_查主体登记详情_平台SubjectView字段() {
        server.expect(requestTo(BASE + "/api/v1/subject/registrations/S001"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess(ok("{\"subjectNo\":\"S001\",\"subjectName\":\"蓝天数科\","
                        + "\"uscc\":\"91330100MA2AB1CD3E\",\"subjectType\":\"ENTERPRISE\","
                        + "\"regAddress\":\"杭州市西湖区\",\"contactName\":\"张工\","
                        + "\"contactPhone\":\"138****0000\",\"status\":\"ADMITTED\",\"statusLogs\":[]}"),
                        MediaType.APPLICATION_JSON));
        var view = new DefaultIdentityCapability(client).querySubject("S001");
        assertThat(view.subjectNo()).isEqualTo("S001");
        assertThat(view.subjectName()).isEqualTo("蓝天数科");
        assertThat(view.status()).isEqualTo("ADMITTED");
        server.verify();
    }

    @Test
    void 身份腿_查DID状态_平台ResolutionView字段() {
        server.expect(requestTo(BASE + "/api/v1/did/did:ctds:S001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("{\"did\":\"did:ctds:S001\",\"status\":\"ACTIVE\","
                        + "\"document\":{\"@context\":[\"https://www.w3.org/ns/did/v1\"]}}"),
                        MediaType.APPLICATION_JSON));
        var view = new DefaultIdentityCapability(client).resolveDid("did:ctds:S001");
        assertThat(view.did()).isEqualTo("did:ctds:S001");
        assertThat(view.status()).isEqualTo("ACTIVE");
        server.verify();
    }

    @Test
    void 资源腿_登记数据集_平台RegisterDatasetRequest与DatasetView字段() {
        server.expect(requestTo(BASE + "/api/v1/data-spaces/1001/datasets"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andExpect(jsonPath("$.name").value("企业信用数据集"))
                .andExpect(jsonPath("$.type").value("DATASET"))
                .andExpect(jsonPath("$.tags[0]").value("企业数据"))
                .andExpect(jsonPath("$.declareCategory").value("企业数据"))
                .andExpect(jsonPath("$.declareLevel").value("L2"))
                .andExpect(jsonPath("$.declareImportant").value(true))
                .andRespond(withSuccess(ok("{\"id\":501,\"dataNo\":\"DS202610010001\",\"spaceId\":1001,"
                        + "\"name\":\"企业信用数据集\",\"type\":\"DATASET\",\"intro\":\"企业信用画像数据\","
                        + "\"tags\":[\"企业数据\"],\"declareCategory\":\"企业数据\",\"declareLevel\":\"L2\","
                        + "\"declareImportant\":true,\"status\":\"ACTIVE\","
                        + "\"createdAt\":\"2026-10-01T10:00:00\"}"), MediaType.APPLICATION_JSON));
        var view = new DefaultResourceCapability(client).registerDataset("1001",
                new RegisterDatasetCommand("企业信用数据集", "DATASET", "企业信用画像数据",
                        List.of("企业数据"), "企业数据", "L2", true));
        assertThat(view.id()).isEqualTo(501L);
        assertThat(view.name()).isEqualTo("企业信用数据集");
        assertThat(view.type()).isEqualTo("DATASET");
        server.verify();
    }

    @Test
    void 资源腿_查我的数据集_返回平台分页信封() {
        server.expect(requestTo(BASE + "/api/v1/datasets/mine?pageNum=2&pageSize=20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("{\"list\":[{\"id\":501,\"dataNo\":\"DS202610010001\","
                        + "\"spaceId\":1001,\"name\":\"企业信用数据集\",\"type\":\"DATASET\","
                        + "\"intro\":\"企业信用画像数据\",\"tags\":[],\"declareCategory\":\"企业数据\","
                        + "\"declareLevel\":\"L2\",\"declareImportant\":false,\"status\":\"ACTIVE\","
                        + "\"createdAt\":\"2026-10-01T10:00:00\"}],\"total\":21,\"pageNum\":2,"
                        + "\"pageSize\":20,\"totalPages\":2}"), MediaType.APPLICATION_JSON));
        PageResult<?> page = new DefaultResourceCapability(client).listMyDatasets(2, 20);
        assertThat(page.total()).isEqualTo(21L);
        assertThat(page.pageNum()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.list()).hasSize(1);
        server.verify();
    }

    @Test
    void 产品腿_发布产品_平台CreateProductRequest与ProviderProductView字段() {
        server.expect(requestTo(BASE + "/api/v1/data-products"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.datasetId").value(501))
                .andExpect(jsonPath("$.productName").value("信用数据 API"))
                .andExpect(jsonPath("$.productType").value("API"))
                .andExpect(jsonPath("$.pricingModel").value("PER_CALL"))
                .andExpect(jsonPath("$.priceAmount").value(10.00))
                .andExpect(jsonPath("$.categoryCode").value("CAT-01"))
                .andRespond(withSuccess(ok("{\"productId\":601,\"productName\":\"信用数据 API\","
                        + "\"intro\":\"按次调用\",\"productType\":\"API接口\",\"pricingModel\":\"按次\","
                        + "\"priceAmount\":\"10.00\",\"status\":\"未上架\",\"providerSubjectNo\":\"S001\","
                        + "\"datasetId\":501,\"categoryCode\":\"CAT-01\",\"categoryName\":\"企业数据\","
                        + "\"listedAt\":null,\"createdAt\":\"2026-10-01T11:00:00\"}"),
                        MediaType.APPLICATION_JSON));
        var view = new DefaultProductCapability(client).publishProduct(
                new CreateProductCommand(501L, "信用数据 API", "按次调用", "API", "PER_CALL",
                        new BigDecimal("10.00"), "CAT-01"));
        assertThat(view.productId()).isEqualTo(601L);
        assertThat(view.productType()).isEqualTo("API接口");
        assertThat(view.status()).isEqualTo("未上架");
        server.verify();
    }

    @Test
    void 产品腿_查我的产品_返回平台分页信封() {
        server.expect(requestTo(BASE + "/api/v1/data-products/mine?pageNum=1&pageSize=10"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("{\"list\":[{\"productId\":601,"
                        + "\"productName\":\"信用数据 API\",\"intro\":\"按次调用\",\"productType\":\"API接口\","
                        + "\"pricingModel\":\"按次\",\"priceAmount\":\"10.00\",\"status\":\"已上架\","
                        + "\"providerSubjectNo\":\"S001\",\"datasetId\":501,\"categoryCode\":\"CAT-01\","
                        + "\"categoryName\":\"企业数据\",\"listedAt\":\"2026-10-01T12:00:00\","
                        + "\"createdAt\":\"2026-10-01T11:00:00\"}],\"total\":1,\"pageNum\":1,"
                        + "\"pageSize\":10,\"totalPages\":1}"), MediaType.APPLICATION_JSON));
        PageResult<?> page = new DefaultProductCapability(client).listMyProducts(1, 10);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.list()).hasSize(1);
        server.verify();
    }

    @Test
    void 合约腿_创建磋商与查详情_平台DealRequests与DealViews字段() {
        server.expect(requestTo(BASE + "/api/v1/contracts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.productId").value(601))
                .andExpect(jsonPath("$.templateNo").value("TPL-001"))
                .andExpect(jsonPath("$.templateVersionNo").value(1))
                .andExpect(jsonPath("$.clauseValues.slots.purpose").value("数据分析"))
                .andRespond(withSuccess(ok("{\"contractNo\":\"CT20261001001\",\"status\":\"NEGOTIATING\","
                        + "\"clauseVersionNo\":1,\"templateNo\":\"TPL-001\",\"templateVersionNo\":1,"
                        + "\"productId\":601}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v1/contracts/CT20261001001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("{\"contractNo\":\"CT20261001001\",\"productId\":601,"
                        + "\"productName\":\"信用数据 API\",\"pricingModel\":\"PER_CALL\","
                        + "\"priceAmount\":10.00,\"providerSubjectNo\":\"S002\","
                        + "\"requesterSubjectNo\":\"S001\",\"templateNo\":\"TPL-001\","
                        + "\"templateVersionNo\":1,\"status\":\"EFFECTIVE\",\"terminationType\":null,"
                        + "\"terminationReason\":null,\"terminatedBy\":null,"
                        + "\"effectiveAt\":\"2026-10-02T09:00:00\",\"endedAt\":null,"
                        + "\"currentClauseVersion\":1,\"clauseValues\":{\"slots\":{}},\"contentHash\":\"h1\","
                        + "\"signatures\":[],\"attestation\":null,"
                        + "\"createdAt\":\"2026-10-01T13:00:00\",\"updatedAt\":\"2026-10-02T09:00:00\"}"),
                        MediaType.APPLICATION_JSON));
        var capability = new DefaultContractCapability(client);
        var created = capability.createNegotiation(new CreateContractCommand(601L, "TPL-001", 1,
                Map.of("slots", Map.of("purpose", "数据分析"))));
        assertThat(created.contractNo()).isEqualTo("CT20261001001");
        assertThat(created.status()).isEqualTo("NEGOTIATING");
        var detail = capability.getContract("CT20261001001");
        assertThat(detail.status()).isEqualTo("EFFECTIVE");
        assertThat(detail.providerSubjectNo()).isEqualTo("S002");
        assertThat(detail.requesterSubjectNo()).isEqualTo("S001");
        server.verify();
    }

    @Test
    void 合约腿_查我的合约_平台DealViewsMine字段() {
        server.expect(requestTo(BASE + "/api/v1/contracts/mine?pageNum=1&pageSize=10"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("{\"list\":[{\"contractNo\":\"CT20261001001\","
                        + "\"productName\":\"信用数据 API\",\"counterpartySubjectNo\":\"S002\","
                        + "\"myRole\":\"REQUESTER\",\"status\":\"EFFECTIVE\",\"currentClauseVersion\":1,"
                        + "\"effectiveAt\":\"2026-10-02T09:00:00\","
                        + "\"updatedAt\":\"2026-10-02T09:00:00\"}],\"total\":1,\"pageNum\":1,"
                        + "\"pageSize\":10,\"totalPages\":1}"), MediaType.APPLICATION_JSON));
        PageResult<?> page = new DefaultContractCapability(client).listMyContracts(1, 10);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.list()).hasSize(1);
        server.verify();
    }

    @Test
    void 用量腿_查用量汇总_平台UsageViewsSummary字段() {
        server.expect(requestTo(BASE + "/api/v1/contracts/CT20261001001/usage-summary"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("{\"contractNo\":\"CT20261001001\","
                        + "\"quota\":{\"limit\":100,\"used\":3},\"allowedCount\":3,\"deniedCount\":1,"
                        + "\"records\":{\"list\":[],\"total\":0,\"pageNum\":1,\"pageSize\":10,"
                        + "\"totalPages\":0}}"), MediaType.APPLICATION_JSON));
        var view = new DefaultUsageControlCapability(client, new DefaultDeliveryCapability(client))
                .getUsageSummary("CT20261001001");
        assertThat(view.contractNo()).isEqualTo("CT20261001001");
        assertThat(view.allowedCount()).isEqualTo(3L);
        assertThat(view.deniedCount()).isEqualTo(1L);
        assertThat(view.quota().limit()).isEqualTo(100);
        assertThat(view.quota().used()).isEqualTo(3);
        server.verify();
    }

    @Test
    void 分页参数越界_本地failFast_不发起平台调用() {
        var capability = new DefaultResourceCapability(client);
        assertThatThrownBy(() -> capability.listMyDatasets(0, 10))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        assertThatThrownBy(() -> capability.listMyDatasets(1, 101))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
        server.verify();
    }
}
