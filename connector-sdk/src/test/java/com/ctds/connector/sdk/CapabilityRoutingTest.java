package com.ctds.connector.sdk;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ctds.connector.sdk.model.ContractView;
import com.ctds.connector.sdk.model.CreateContractCommand;
import com.ctds.connector.sdk.model.CreateProductCommand;
import com.ctds.connector.sdk.model.DatasetView;
import com.ctds.connector.sdk.model.ProductView;
import com.ctds.connector.sdk.model.RegisterDatasetCommand;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 六门面路由契约（hifi T2~T7 / §5 端点映射表）：路径 / Bearer 头 / 载荷字段 / 视图解析
 * ——mock 平台响应逐腿断言（零运行态依赖）。
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
    void 身份腿_查主体登记详情() {
        server.expect(requestTo(BASE + "/api/v1/subject/registrations/S001"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess(ok(
                        "{\"subjectNo\":\"S001\",\"name\":\"蓝天数科\",\"status\":\"ADMITTED\"}"),
                        MediaType.APPLICATION_JSON));
        var view = new DefaultIdentityCapability(client).querySubject("S001");
        org.assertj.core.api.Assertions.assertThat(view.status()).isEqualTo("ADMITTED");
        server.verify();
    }

    @Test
    void 身份腿_查DID状态() {
        server.expect(requestTo(BASE + "/api/v1/did/did:ctds:S001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("{\"did\":\"did:ctds:S001\",\"status\":\"VALID\"}"),
                        MediaType.APPLICATION_JSON));
        var view = new DefaultIdentityCapability(client).resolveDid("did:ctds:S001");
        org.assertj.core.api.Assertions.assertThat(view.status()).isEqualTo("VALID");
        server.verify();
    }

    @Test
    void 资源腿_登记数据集() {
        server.expect(requestTo(BASE + "/api/v1/data-spaces/1001/datasets"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andExpect(jsonPath("$.name").value("企业信用数据集"))
                .andExpect(jsonPath("$.type").value("结构化"))
                .andExpect(jsonPath("$.declareLevel").value("L2"))
                .andRespond(withSuccess(ok("{\"datasetId\":501,\"name\":\"企业信用数据集\",\"status\":\"REGISTERED\"}"),
                        MediaType.APPLICATION_JSON));
        var view = new DefaultResourceCapability(client).registerDataset("1001",
                new RegisterDatasetCommand("企业信用数据集", "结构化", "简介", "企业数据", "L2"));
        org.assertj.core.api.Assertions.assertThat(view.datasetId()).isEqualTo(501L);
        server.verify();
    }

    @Test
    void 资源腿_查我的数据集() {
        server.expect(requestTo(BASE + "/api/v1/datasets/mine"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("[{\"datasetId\":501,\"name\":\"企业信用数据集\",\"status\":\"REGISTERED\"}]"),
                        MediaType.APPLICATION_JSON));
        List<DatasetView> views = new DefaultResourceCapability(client).listMyDatasets();
        org.assertj.core.api.Assertions.assertThat(views).hasSize(1);
        server.verify();
    }

    @Test
    void 产品腿_发布产品() {
        server.expect(requestTo(BASE + "/api/v1/data-products"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.datasetId").value(501))
                .andExpect(jsonPath("$.productName").value("信用数据 API"))
                .andExpect(jsonPath("$.productType").value("API"))
                .andRespond(withSuccess(ok("{\"productId\":601,\"productName\":\"信用数据 API\",\"status\":\"PUBLISHED\"}"),
                        MediaType.APPLICATION_JSON));
        var view = new DefaultProductCapability(client).publishProduct(
                new CreateProductCommand(501L, "信用数据 API", "简介", "API", "按次"));
        org.assertj.core.api.Assertions.assertThat(view.productId()).isEqualTo(601L);
        server.verify();
    }

    @Test
    void 产品腿_查我的产品() {
        server.expect(requestTo(BASE + "/api/v1/data-products/mine"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok(
                        "[{\"productId\":601,\"productName\":\"信用数据 API\",\"status\":\"PUBLISHED\"}]"),
                        MediaType.APPLICATION_JSON));
        List<ProductView> views = new DefaultProductCapability(client).listMyProducts();
        org.assertj.core.api.Assertions.assertThat(views).hasSize(1);
        server.verify();
    }

    @Test
    void 合约腿_创建磋商与查询() {
        server.expect(requestTo(BASE + "/api/v1/contracts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.productId").value(601))
                .andExpect(jsonPath("$.purpose").value("数据分析"))
                .andRespond(withSuccess(ok(
                        "{\"contractNo\":\"CT001\",\"applicantNo\":\"S001\","
                                + "\"providerNo\":\"S002\",\"status\":\"NEGOTIATING\"}"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v1/contracts/CT001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok(
                        "{\"contractNo\":\"CT001\",\"applicantNo\":\"S001\","
                                + "\"providerNo\":\"S002\",\"status\":\"EFFECTIVE\"}"),
                        MediaType.APPLICATION_JSON));
        var capability = new DefaultContractCapability(client);
        var created = capability.createNegotiation(new CreateContractCommand(601L, "数据分析", "境内"));
        org.assertj.core.api.Assertions.assertThat(created.contractNo()).isEqualTo("CT001");
        var detail = capability.getContract("CT001");
        org.assertj.core.api.Assertions.assertThat(detail.status()).isEqualTo("EFFECTIVE");
        server.verify();
    }

    @Test
    void 合约腿_查我的合约() {
        server.expect(requestTo(BASE + "/api/v1/contracts/mine"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok(
                        "[{\"contractNo\":\"CT001\",\"applicantNo\":\"S001\","
                                + "\"providerNo\":\"S002\",\"status\":\"EFFECTIVE\"}]"),
                        MediaType.APPLICATION_JSON));
        List<ContractView> views = new DefaultContractCapability(client).listMyContracts();
        org.assertj.core.api.Assertions.assertThat(views).hasSize(1);
        server.verify();
    }

    @Test
    void 用量腿_查用量汇总() {
        server.expect(requestTo(BASE + "/api/v1/contracts/CT001/usage-summary"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("{\"contractNo\":\"CT001\",\"usedCount\":3}"),
                        MediaType.APPLICATION_JSON));
        var view = new DefaultUsageControlCapability(client)
                .getUsageSummary("CT001");
        org.assertj.core.api.Assertions.assertThat(view.usedCount()).isEqualTo(3L);
        server.verify();
    }
}
