package com.ctds.common.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.web.GlobalExceptionHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.RestController;

/** B2/B3：注解式 RBAC 全链路（过滤器→拦截器→异常映射 200/401/403）与既有 400 契约回归。 */
class AuthEnforcementWebTest {

    private final List<AuditEvent> auditEvents = new ArrayList<>();
    private MockMvc mockMvc;

    @RestController
    static class FixtureController {

        @GetMapping("/api/v1/items")
        @RequirePermission("item.read")
        String list() {
            return "ok";
        }

        @DeleteMapping("/api/v1/items/1")
        @RequirePermission("item.delete")
        String remove() {
            return "deleted";
        }

        @GetMapping("/api/v1/blank")
        @RequirePermission("  ")
        String blank() {
            return "never";
        }

        @GetMapping("/api/v1/public")
        String open() {
            return "public";
        }

        @GetMapping("/api/v1/boom")
        String boom() {
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数不合法");
        }
    }

    @BeforeEach
    void setUp() {
        final AuthProperties properties = new AuthProperties();
        properties.setPermissions(Map.of("user", "item.read", "admin", "item.read,item.delete"));
        final RolePermissionMapper mapper = new ConfigRolePermissionMapper(properties.getPermissions());
        final AccessControl accessControl = new DefaultAccessControl(mapper, auditEvents::add, true);
        mockMvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .addFilters(new AuthContextFilter(properties))
                .addInterceptors(new PermissionInterceptor(accessControl))
                .setControllerAdvice(new AuthAdvice(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void userCanReadButCannotDelete() throws Exception {
        mockMvc.perform(get("/api/v1/items").header("X-Ctds-Subject", "u-1").header("X-Ctds-Roles", "user"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/items/1").header("X-Ctds-Subject", "u-1").header("X-Ctds-Roles", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"))
                .andExpect(jsonPath("$.message").value("无权限执行该操作"))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertTrue(auditEvents.stream().anyMatch(e -> "rbac.check".equals(e.action())
                && "u-1".equals(e.actor()) && "item.delete".equals(e.detail().get("permission"))));
    }

    @Test
    void adminCanReadAndDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/items/1")
                        .header("X-Ctds-Subject", "a-1").header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk());
    }

    @Test
    void missingIdentityOnProtectedEndpointShouldReturn401WithEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1000C0002"))
                .andExpect(jsonPath("$.message").value("认证失败或身份已失效"));
    }

    @Test
    void unprotectedEndpointShouldNotRequireIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/public")).andExpect(status().isOk());
    }

    @Test
    void nonAuthBizExceptionStillMapsTo400ViaGlobalHandler() throws Exception {
        // 既有契约回归：AuthAdvice（@Order(100) 只接 AuthException）不改变普通 BizException 的 C→400 默认映射
        mockMvc.perform(get("/api/v1/boom"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"))
                .andExpect(jsonPath("$.message").value("参数不合法"));
    }

    @Test
    void blankPermissionAnnotationShouldFailLoud() throws Exception {
        final AccessControl accessControl = new DefaultAccessControl(
                new ConfigRolePermissionMapper(Map.of()), auditEvents::add, true);
        final Method method = FixtureController.class.getDeclaredMethod("blank");
        final PermissionInterceptor interceptor = new PermissionInterceptor(accessControl);

        assertThrows(IllegalStateException.class, () -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                new HandlerMethod(new FixtureController(), method)));
    }

    @Test
    void nonHandlerMethodShouldPassThrough() throws Exception {
        final PermissionInterceptor interceptor = new PermissionInterceptor(
                new DefaultAccessControl(new ConfigRolePermissionMapper(Map.of()), null, true));

        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                "resource-handler"));
    }
}
