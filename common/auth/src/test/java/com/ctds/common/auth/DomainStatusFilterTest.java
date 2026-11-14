package com.ctds.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WBS-3.9.3 T5/T6/T7（servlet 腿）：退役拦截 503 + `1013C0004` + 精确文案；豁免面放行；
 * 缺省 ACTIVE 零行为；恢复 ACTIVE 业务自动恢复（hifi §2 / §4 边界 1~4）。
 */
class DomainStatusFilterTest {

    @RestController
    static class FixtureController {
        @GetMapping("/api/v1/business/items")
        String business() {
            return "ok";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "{\"status\":\"UP\"}";
        }

        @GetMapping("/api/v1/evidence/audit/overview")
        String auditRead() {
            return "audit";
        }

        @GetMapping("/api/v1/platform/domain")
        String domainRead() {
            return "domain";
        }

        @GetMapping("/api/v1/health/evidence/live")
        String customHealth() {
            return "live";
        }
    }

    private MockMvc mockMvcWith(DomainProperties properties) {
        return MockMvcBuilders.standaloneSetup(new FixtureController())
                .addFilters(new DomainStatusFilter(properties))
                .build();
    }

    @Test
    void defaultActiveKeepsZeroBehavior() throws Exception {
        MockMvc mockMvc = mockMvcWith(new DomainProperties());
        mockMvc.perform(get("/api/v1/business/items")).andExpect(status().isOk());
    }

    @Test
    void retiredBlocksBusinessWith503AndReservedCode() throws Exception {
        DomainProperties properties = new DomainProperties();
        properties.setStatus("RETIRED");
        MockMvc mockMvc = mockMvcWith(properties);
        mockMvc.perform(get("/api/v1/business/items"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("1013C0004"))
                .andExpect(jsonPath("$.message").value("部署域已退役"));
    }

    @Test
    void retiredExemptsActuatorAuditAndDomainReadPaths() throws Exception {
        DomainProperties properties = new DomainProperties();
        properties.setStatus("RETIRED");
        properties.getRetired().getPermitPathPatterns().add("/api/v1/evidence/audit/**");
        properties.getRetired().getPermitPathPatterns().add("/api/v1/platform/**");
        MockMvc mockMvc = mockMvcWith(properties);
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/evidence/audit/overview")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/platform/domain")).andExpect(status().isOk());
    }

    @Test
    void customAntPatternIsHonoured() throws Exception {
        DomainProperties properties = new DomainProperties();
        properties.setStatus("RETIRED");
        properties.getRetired().getPermitPathPatterns().add("/api/v1/health/*/live");
        MockMvc mockMvc = mockMvcWith(properties);
        mockMvc.perform(get("/api/v1/health/evidence/live")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/health/evidence/ready"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/v1/business/items"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void restoreToActiveResumesBusinessWithoutCodeChanges() throws Exception {
        DomainProperties properties = new DomainProperties();
        properties.setStatus("RETIRED");
        MockMvc retired = mockMvcWith(properties);
        retired.perform(get("/api/v1/business/items"))
                .andExpect(status().isServiceUnavailable());
        properties.setStatus("ACTIVE");
        MockMvc restored = mockMvcWith(properties);
        restored.perform(get("/api/v1/business/items")).andExpect(status().isOk());
    }

    @Test
    void blockedResponseIsJsonEnvelopeWithDashTraceWhenAbsent() throws Exception {
        DomainProperties properties = new DomainProperties();
        properties.setStatus("RETIRED");
        String body = mockMvcWith(properties).perform(get("/api/v1/business/items"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"traceId\":\"-\"");
    }
}
