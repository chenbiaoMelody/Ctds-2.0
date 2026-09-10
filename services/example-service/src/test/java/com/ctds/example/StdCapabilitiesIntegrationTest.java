package com.ctds.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.common.api.ApiResult;
import com.ctds.common.web.GlobalExceptionHandler;
import com.ctds.std.StdAdapterErrorCodes;
import com.ctds.std.StdDomain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

/**
 * H6 演示接线集成（真实 Spring 上下文 + 真实 std-adapter 占位实现）：
 * ①探活端点三域齐全且全部未开放（PO 用浏览器/curl 可验证）；
 * ②1003C0001 经 GlobalExceptionHandler 出站 = 400 + 统一文案（不含内部实现）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class StdCapabilitiesIntegrationTest {

    private static final String CAPABILITIES_URL = "/api/v1/std-capabilities";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    @Test
    void 探活端点返回三标准域状态且全部未开放() throws Exception {
        mockMvc.perform(get(CAPABILITIES_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].code").value("interconnect"))
                .andExpect(jsonPath("$.data[0].name").value("互联互通"))
                .andExpect(jsonPath("$.data[0].implemented").value(false))
                .andExpect(jsonPath("$.data[0].message").value(
                        "该能力域尚未开放：区域枢纽对接与产品互挂接口将按信通院互联互通规范由后续工作包实现（WBS 4.x）"))
                .andExpect(jsonPath("$.data[1].code").value("did"))
                .andExpect(jsonPath("$.data[1].name").value("跨空间身份互认"))
                .andExpect(jsonPath("$.data[1].implemented").value(false))
                .andExpect(jsonPath("$.data[2].code").value("evidence"))
                .andExpect(jsonPath("$.data[2].name").value("测评证据"))
                .andExpect(jsonPath("$.data[2].implemented").value(false));
    }

    @Test
    void 未开放错误码封套与统一文案出站正确() {
        final ResponseEntity<ApiResult<Void>> resp = globalExceptionHandler.onBizException(
                StdAdapterErrorCodes.notImplemented(StdDomain.INTERCONNECT));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("1003C0001");
        assertThat(resp.getBody().message()).isEqualTo("该标准互联功能尚未开放");
        // 对外文案不泄露域标识与内部实现（红线：章程 4.3）
        assertThat(resp.getBody().message()).doesNotContain("interconnect", "Placeholder", "std");
    }
}
