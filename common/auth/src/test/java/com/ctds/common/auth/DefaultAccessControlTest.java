package com.ctds.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** B4/B5/B6：判定内核（注解与代码内调用共用）+ RBAC 拒绝联动审计 DENIED（含开关关闭不记）。 */
class DefaultAccessControlTest {

    private final List<AuditEvent> auditEvents = new ArrayList<>();
    private final AuditRecorder recorder = auditEvents::add;
    private ConfigRolePermissionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ConfigRolePermissionMapper(Map.of("user", "greeting.read",
                "admin", "greeting.read,greeting.delete"));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private DefaultAccessControl accessControl() {
        return new DefaultAccessControl(mapper, recorder, true);
    }

    private void login(String subject, String... roles) {
        AuthContext.set(new AuthUser(subject, Set.of(roles)));
    }

    @Test
    void unknownRoleShouldHaveNoPermissions() {
        assertEquals(Set.of(), mapper.permissionsOf("auditor"));
        assertEquals(Set.of("greeting.read", "greeting.delete"), mapper.permissionsOf("admin"));
    }

    @Test
    void hasPermissionShouldMatchAnyRoleUnion() {
        login("u-1", "user");
        assertTrue(accessControl().hasPermission("greeting.read"));
        assertFalse(accessControl().hasPermission("greeting.delete"));

        login("u-2", "user", "admin");
        assertTrue(accessControl().hasPermission("greeting.delete"), "多角色取并集");
    }

    @Test
    void requireWithoutIdentityShouldThrowUnauthorizedWithoutAudit() {
        final AuthException ex = assertThrows(AuthException.class,
                () -> accessControl().require("greeting.read"));

        assertEquals(ErrorCodes.UNAUTHORIZED, ex.getErrorCode());
        assertEquals(AuthAdvice.UNAUTHORIZED_MESSAGE, ex.getMessage());
        assertTrue(auditEvents.isEmpty(), "未认证由网关层负责记审计，服务侧 require 不记 DENIED");
    }

    @Test
    void requireWithoutPermissionShouldThrowForbiddenAndRecordDeniedAudit() {
        login("u-1", "user");

        final AuthException ex = assertThrows(AuthException.class,
                () -> accessControl().require("greeting.delete"));

        assertEquals(ErrorCodes.FORBIDDEN, ex.getErrorCode());
        assertEquals(AuthAdvice.FORBIDDEN_MESSAGE, ex.getMessage());
        assertEquals(1, auditEvents.size());
        final AuditEvent event = auditEvents.get(0);
        assertEquals("rbac.check", event.action());
        assertEquals(AuditOutcome.DENIED, event.outcome());
        assertEquals("u-1", event.actor());
        assertEquals("greeting.delete", event.detail().get("permission"));
        assertFalse(event.detail().containsKey("token"), "审计明细禁含令牌类字段");
    }

    @Test
    void auditDisabledShouldNotRecord() {
        login("u-1", "user");
        final DefaultAccessControl control = new DefaultAccessControl(mapper, recorder, false);

        assertThrows(AuthException.class, () -> control.require("greeting.delete"));
        assertTrue(auditEvents.isEmpty());
    }

    @Test
    void absentRecorderBeanShouldNotFailJudgement() {
        login("u-1", "user");
        final DefaultAccessControl control = new DefaultAccessControl(mapper, null, true);

        assertThrows(AuthException.class, () -> control.require("greeting.delete"));
        assertFalse(control.hasPermission("greeting.delete"));
    }
}
