package com.ctds.common.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/biz-client")
        public String bizClient() {
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数不能为空");
        }

        @GetMapping("/biz-system")
        public String bizSystem() {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "数据库连接失败 host=10.0.0.1");
        }

        @GetMapping("/unknown")
        public String unknown() {
            throw new IllegalStateException("secret internal detail");
        }

        @PostMapping("/create-only")
        public String createOnly(@RequestBody final Map<String, String> body) {
            return "ok";
        }
    }

    @Test
    void bizClientShouldReturn400WithCodeAndBusinessMessage() throws Exception {
        mockMvc.perform(get("/biz-client"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"))
                .andExpect(jsonPath("$.message").value("参数不能为空"))
                .andExpect(jsonPath("$.traceId").value("-"));
    }

    @Test
    void bizSystemShouldReturn500AndMaskInternalDetail() throws Exception {
        mockMvc.perform(get("/biz-system"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("1000S9999"))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"))
                .andExpect(jsonPath("$.message").value(not(containsString("10.0.0.1"))));
    }

    @Test
    void noResourceShouldReturn404WithNotFoundCode() {
        final ResponseEntity<ApiResult<Void>> response = new GlobalExceptionHandler()
                .onNoResource(new NoResourceFoundException(HttpMethod.GET, "missing.txt"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("1000C0003", response.getBody().code());
        assertEquals("资源不存在", response.getBody().message());
    }

    @Test
    void unknownShouldReturn500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/unknown"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("1000S9999"))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"))
                .andExpect(jsonPath("$.message").value(not(containsString("secret"))));
    }

    @Test
    void methodNotSupportedShouldReturn405() throws Exception {
        mockMvc.perform(get("/create-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("1000C0004"))
                .andExpect(jsonPath("$.message").value("请求方法不支持"));
    }

    @Test
    void unreadableBodyShouldReturn400WithParamCode() throws Exception {
        mockMvc.perform(post("/create-only").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"))
                .andExpect(jsonPath("$.message").value("请求体格式不合法"));
    }
}
