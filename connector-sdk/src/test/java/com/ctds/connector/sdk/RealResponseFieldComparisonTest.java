package com.ctds.connector.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ctds.connector.sdk.model.ContractView;
import com.ctds.connector.sdk.model.DatasetView;
import com.ctds.connector.sdk.model.DeliveryView;
import com.ctds.connector.sdk.model.PageResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 运行期字段级比对（WBS-3.6.8 W2 / T10，hifi §2；ADR-026 §4-1）：
 * 门面映射样本 = <b>走查环境真实响应 JSON 原文</b>（2026-10-31 经网关 8086 实测采集，
 * 探针证据 {@code build-output/w368-probe-chain*.log}）——mock 样本升级为真实样本，
 * 断言 SDK 收窄视图逐字段与真实响应一致（多余字段安全忽略 = 收窄契约显式化）。
 * 主代码零改动；14 方法路径核对由 {@link CapabilityRoutingTest}（10 方法）与
 * {@code DeliveryCapabilityTest} / {@code UsageControlFailClosedTest}（其余 4 方法）共同承载。
 */
class RealResponseFieldComparisonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE = "http://platform.test";

    private final RestClient.Builder restBuilder = RestClient.builder().baseUrl(BASE);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restBuilder).build();

    private final PlatformClient client = new PlatformClient(restBuilder, new StaticTokenProvider("tok-1"));

    private final DefaultDeliveryCapability delivery = new DefaultDeliveryCapability(client);

    private final DefaultContractCapability contract = new DefaultContractCapability(client);

    private final DefaultResourceCapability resource = new DefaultResourceCapability(client);

    /** 真实响应（GET /api/v1/deliveries/DL000023 经网关实测；CONNECTOR 形态交付行）。 */
    private static final String REAL_DELIVERY_JSON = "{\"code\":\"0\",\"message\":\"success\","
            + "\"traceId\":\"-\",\"data\":{\"deliveryNo\":\"DL000023\",\"contractNo\":\"CO000030\","
            + "\"productId\":13,\"productName\":\"普惠金融服务\",\"deliveryType\":\"CONNECTOR\","
            + "\"requesterNo\":\"S20260922000001\",\"providerNo\":\"S20260925000001\","
            + "\"fileId\":\"CN000002:SR000003\",\"fileName\":null,\"fileSize\":25,"
            + "\"packageId\":\"DL000023.sm4\","
            + "\"checksum\":\"3ccc995f4bfaf5d45a51b4f189084f5ad49a17885ae68373eded5f283d8ce066\","
            + "\"result\":\"SUCCESS\",\"createdAt\":\"2026-10-31T10:10:04\"}}";

    /** 真实响应（GET /api/v1/contracts/mine 经网关实测；PROVIDER 视角行）。 */
    private static final String REAL_CONTRACT_JSON = "{\"code\":\"0\",\"message\":\"success\","
            + "\"traceId\":\"-\",\"data\":{\"list\":[{\"contractNo\":\"CO000030\","
            + "\"productName\":\"普惠金融服务\",\"counterpartySubjectNo\":\"S20260922000001\","
            + "\"myRole\":\"PROVIDER\",\"status\":\"EFFECTIVE\",\"currentClauseVersion\":1,"
            + "\"effectiveAt\":\"2026-10-31T10:02:01\",\"updatedAt\":\"2026-10-31T10:02:00\"}],"
            + "\"total\":16,\"pageNum\":1,\"pageSize\":1,\"totalPages\":16}}";

    /** 真实响应（GET /api/v1/datasets/mine 经网关实测；行值中文按真实形态保留）。 */
    private static final String REAL_DATASETS_JSON = "{\"code\":\"0\",\"message\":\"success\","
            + "\"traceId\":\"-\",\"data\":{\"list\":[{\"id\":15,\"dataNo\":\"DS20261004000006\","
            + "\"spaceId\":2,\"name\":\"\u8054\u8c03\u8d44\u6e90-337-C\",\"type\":\"DATASET\","
            + "\"intro\":\"\u8054\u8c03\u63a2\u9488\u8d44\u6e90C\",\"tags\":[\"\u91d1\u878d\"],"
            + "\"declareCategory\":\"\u91d1\u878d\",\"declareLevel\":\"L2\","
            + "\"declareImportant\":false,\"status\":\"ACTIVE\","
            + "\"createdAt\":\"2026-10-04T21:16:08\"}],\"total\":3,\"pageNum\":1,\"pageSize\":1}}";

    @Test
    void deliveryViewMapsRealConnectorDeliveryRow() {
        server.expect(requestTo(BASE + "/api/v1/deliveries/DL000023"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(REAL_DELIVERY_JSON, MediaType.APPLICATION_JSON));
        final DeliveryView view = delivery.getDelivery("DL000023");
        assertThat(view.deliveryNo()).isEqualTo("DL000023");
        assertThat(view.contractNo()).isEqualTo("CO000030");
        assertThat(view.productId()).isEqualTo(13L);
        assertThat(view.productName()).isEqualTo("普惠金融服务");
        assertThat(view.deliveryType()).as("CONNECTOR 形态经网关真实回传").isEqualTo("CONNECTOR");
        assertThat(view.result()).isEqualTo("SUCCESS");
        // 样本字段集契约：真实样本 data 字段面 = 视图承载 6 字段 + 有意收窄忽略 8 字段（显式清单，
        // 样本新增/删减字段须同步更新本断言——评审采纳：收窄视图对未断言字段漂移的预警）
        assertThat(fieldNamesOf(parse(REAL_DELIVERY_JSON).path("data")))
                .containsExactlyInAnyOrder("deliveryNo", "contractNo", "productId", "productName",
                        "deliveryType", "requesterNo", "providerNo", "fileId", "fileName",
                        "fileSize", "packageId", "checksum", "result", "createdAt");
        server.verify();
    }

    @Test
    void contractViewMapsRealMineRow() {
        server.expect(requestTo(BASE + "/api/v1/contracts/mine?pageNum=1&pageSize=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(REAL_CONTRACT_JSON, MediaType.APPLICATION_JSON));
        final PageResult<ContractView> page = contract.listMyContracts(1, 1);
        assertThat(page.list()).hasSize(1);
        final ContractView view = page.list().get(0);
        assertThat(view.contractNo()).isEqualTo("CO000030");
        assertThat(view.status()).isEqualTo("EFFECTIVE");
        assertThat(view.myRole()).isEqualTo("PROVIDER");
        assertThat(view.counterpartySubjectNo()).isEqualTo("S20260922000001");
        assertThat(view.productName()).isEqualTo("普惠金融服务");
        // 样本字段集契约：分页信封 5 字段 + 行 8 字段 = 视图承载 5 + 有意忽略 3（行内无 productId 等视图字段）
        assertThat(fieldNamesOf(parse(REAL_CONTRACT_JSON).path("data")))
                .containsExactlyInAnyOrder("list", "total", "pageNum", "pageSize", "totalPages");
        assertThat(fieldNamesOf(parse(REAL_CONTRACT_JSON)
                .path("data").path("list").get(0)))
                .containsExactlyInAnyOrder("contractNo", "productName", "counterpartySubjectNo",
                        "myRole", "status", "currentClauseVersion", "effectiveAt", "updatedAt");
        server.verify();
    }

    @Test
    void datasetViewMapsRealMineRow() {
        server.expect(requestTo(BASE + "/api/v1/datasets/mine?pageNum=1&pageSize=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(REAL_DATASETS_JSON, MediaType.APPLICATION_JSON));
        final PageResult<DatasetView> page = resource.listMyDatasets(1, 1);
        assertThat(page.list()).hasSize(1);
        final DatasetView view = page.list().get(0);
        assertThat(view.id()).isEqualTo(15L);
        assertThat(view.name()).isEqualTo("联调资源-337-C");
        assertThat(view.type()).isEqualTo("DATASET");
        assertThat(view.intro()).as("视图承载字段逐字段断言补齐").isEqualTo("联调探针资源C");
        // 样本字段集契约：分页信封 4 字段（无 totalPages）+ 行 12 字段 = 视图承载 4 + 有意忽略 8
        assertThat(fieldNamesOf(parse(REAL_DATASETS_JSON).path("data")))
                .containsExactlyInAnyOrder("list", "total", "pageNum", "pageSize");
        assertThat(fieldNamesOf(parse(REAL_DATASETS_JSON)
                .path("data").path("list").get(0)))
                .containsExactlyInAnyOrder("id", "dataNo", "spaceId", "name", "type", "intro",
                        "tags", "declareCategory", "declareLevel", "declareImportant", "status",
                        "createdAt");
        server.verify();
    }

    /** 内置真实样本解析（样本为编译期常量，解析失败 = 测试样本常量错误，快速失败）。 */
    private static JsonNode parse(final String json) {
        try {
            return MAPPER.readTree(json);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("内置真实样本非法（测试样本常量错误）", e);
        }
    }

    /** 样本字段名收集（有序集）：供"样本字段面 = 显式清单"契约断言。 */
    private static Set<String> fieldNamesOf(final JsonNode node) {
        final Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
