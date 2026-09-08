package com.ctds.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** B1：身份上下文读取与清理（网关注入头→AuthContext，请求结束清理；含头边界）。 */
class AuthContextFilterTest {

    private AuthProperties properties;
    private AuthContextFilter filter;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        filter = new AuthContextFilter(properties);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private AuthUser runAndCapture(MockHttpServletRequest request) throws ServletException, IOException {
        final AuthUser[] captured = new AuthUser[1];
        final MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException {
                captured[0] = AuthContext.user();
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return captured[0];
    }

    @Test
    void headersShouldPopulateContextWithinRequestOnly() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Ctds-Subject", "u-1");
        request.addHeader("X-Ctds-Roles", "admin, user ,user,");

        final AuthUser user = runAndCapture(request);

        assertEquals("u-1", user.subject());
        assertEquals(List.of("admin", "user"), List.copyOf(user.roles()));
        assertNull(AuthContext.user(), "请求结束后上下文必须已清理（防线程池串号）");
    }

    @Test
    void missingHeadersShouldYieldEmptyContext() throws Exception {
        assertNull(runAndCapture(new MockHttpServletRequest()));
    }

    @Test
    void oversizedSubjectShouldDropWholeContext() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        // 边界用设计字面量 128（评审④P3-3：不引用实现常量，防镜像漂移）
        request.addHeader("X-Ctds-Subject", "s".repeat(129));
        request.addHeader("X-Ctds-Roles", "user");

        assertNull(runAndCapture(request), "subject 超长 → 整头作废按未认证");
    }

    @Test
    void oversizedRolesShouldDropWholeContext() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Ctds-Subject", "u-1");
        request.addHeader("X-Ctds-Roles", "r".repeat(513));

        assertNull(runAndCapture(request), "roles 合计超长 → 整头作废按未认证");
    }

    @Test
    void repeatedHeadersShouldUseFirstValue() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Ctds-Subject", "first");
        request.addHeader("X-Ctds-Subject", "second");

        final AuthUser user = runAndCapture(request);

        assertEquals("first", user.subject(), "同名头多次出现取第一个（hifi 解析规则）");
    }

    @Test
    void contextShouldBeClearedEvenWhenChainThrows() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Ctds-Subject", "u-1");
        final MockFilterChain throwingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException {
                throw new java.io.IOException("boom");
            }
        };

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> filter.doFilter(request, new MockHttpServletResponse(), throwingChain));
        assertNull(AuthContext.user(), "链异常路径也必须清理上下文（finally 兜底）");
    }

    @Test
    void rolesOverItemCapShouldKeepFirstThirtyTwo() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Ctds-Subject", "u-1");
        request.addHeader("X-Ctds-Roles",
                String.join(",", java.util.stream.IntStream.rangeClosed(0, 40).mapToObj(i -> "role" + i).toList()));

        final AuthUser user = runAndCapture(request);

        // 字面量 32（评审④P3-3）
        assertEquals(32, user.roles().size());
        assertTrue(user.roles().contains("role0"));
        assertFalse(user.roles().contains("role40"), "超过 32 项的部分应被丢弃");
    }

    @Test
    void customHeaderNamesShouldBeHonored() throws Exception {
        properties.getHeader().setSubject("X-Who");
        properties.getHeader().setRoles("X-Grants");
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Who", "u-2");
        request.addHeader("X-Grants", "user");

        final AuthUser user = runAndCapture(request);

        assertEquals("u-2", user.subject());
        assertTrue(user.roles().contains("user"));
    }
}
