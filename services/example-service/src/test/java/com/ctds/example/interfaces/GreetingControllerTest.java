package com.ctds.example.interfaces;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.common.web.GlobalExceptionHandler;
import com.ctds.common.web.TraceIdFilter;
import com.ctds.example.application.GreetingService;
import com.ctds.example.infrastructure.InMemoryGreetingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GreetingControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new GreetingController(new GreetingService(new InMemoryGreetingRepository(), event -> { })))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter()).build();
    }

    @Test
    void createShouldReturnEnvelopeWithCodeZero() throws Exception {
        mockMvc.perform(post("/api/v1/greetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "t-123")
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.traceId").value("t-123"))
                .andExpect(jsonPath("$.data.message").value("hi"));
    }

    @Test
    void createShouldUsePlaceholderWhenTraceIdMissing() throws Exception {
        mockMvc.perform(post("/api/v1/greetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.traceId").value("-"));
    }

    @Test
    void createShouldReturn400WithParamCodeWhenMessageBlank() throws Exception {
        mockMvc.perform(post("/api/v1/greetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"))
                .andExpect(jsonPath("$.message").value("message must not be null or blank"));
    }

    @Test
    void listShouldReturnPagedEnvelope() throws Exception {
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository(), event -> { });
        service.greet("one");
        service.greet("two");
        service.greet("three");
        mockMvc = MockMvcBuilders.standaloneSetup(new GreetingController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter()).build();

        mockMvc.perform(get("/api/v1/greetings")
                        .param("pageNum", "2")
                        .param("pageSize", "2")
                        .param("orderBy", "message"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.pageNum").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(1));
    }

    @Test
    void listShouldReturn400WhenPageSizeOverLimit() throws Exception {
        mockMvc.perform(get("/api/v1/greetings").param("pageSize", "200"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"))
                .andExpect(jsonPath("$.message").value("pageSize 必须在 1~100 之间"));
    }

    @Test
    void listShouldSortByOrderByDesc() throws Exception {
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository(), event -> { });
        service.greet("banana");
        service.greet("apple");
        service.greet("cherry");
        mockMvc = MockMvcBuilders.standaloneSetup(new GreetingController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter()).build();

        mockMvc.perform(get("/api/v1/greetings")
                        .param("orderBy", "message,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].message").value("cherry"))
                .andExpect(jsonPath("$.data.list[1].message").value("banana"))
                .andExpect(jsonPath("$.data.list[2].message").value("apple"));
    }

    @Test
    void listShouldReturnEmptyListWhenPageNumBeyondData() throws Exception {
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository(), event -> { });
        service.greet("only");
        mockMvc = MockMvcBuilders.standaloneSetup(new GreetingController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter()).build();

        mockMvc.perform(get("/api/v1/greetings").param("pageNum", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list.length()").value(0));
    }

    @Test
    void listShouldReturn400ForUnsupportedSortField() throws Exception {
        mockMvc.perform(get("/api/v1/greetings").param("orderBy", "secret_column"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"))
                .andExpect(jsonPath("$.message").value("不支持的排序字段"));
    }
}
