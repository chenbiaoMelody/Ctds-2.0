package com.ctds.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** B5：配置来源映射的边界（未知角色空集、名称超长按不命中、空白容忍、结果不可变）。 */
class ConfigRolePermissionMapperTest {

    @Test
    void unknownOrLongRoleShouldReturnEmpty() {
        final ConfigRolePermissionMapper mapper = new ConfigRolePermissionMapper(Map.of(
                "user", "a.read",
                "r".repeat(65), "x.y"));

        assertTrue(mapper.permissionsOf("auditor").isEmpty());
        assertTrue(mapper.permissionsOf("r".repeat(65)).isEmpty(), "角色名 >64 字符按不命中");
        assertEquals("a.read", mapper.permissionsOf("user").iterator().next());
    }

    @Test
    void longPermissionSegmentShouldBeDropped() {
        final ConfigRolePermissionMapper mapper = new ConfigRolePermissionMapper(Map.of(
                "user", "a.read, " + "p".repeat(65)));

        assertTrue(mapper.permissionsOf("user").contains("a.read"));
        assertEquals(1, mapper.permissionsOf("user").size());
    }

    @Test
    void blankAndNullEntriesShouldBeIgnored() {
        final Map<String, String> raw = new HashMap<>();
        raw.put(" ", "a.read");
        raw.put("user", null);
        raw.put("admin", " , b.read,,");
        final ConfigRolePermissionMapper mapper = new ConfigRolePermissionMapper(raw);

        assertTrue(mapper.permissionsOf(" ").isEmpty());
        assertTrue(mapper.permissionsOf("user").isEmpty());
        assertEquals(1, mapper.permissionsOf("admin").size());
    }

    @Test
    void permissionSetsShouldBeImmutable() {
        // 评审④P3-4：补齐"不可变"断言（类注释原称覆盖但无断言）
        final ConfigRolePermissionMapper mapper = new ConfigRolePermissionMapper(Map.of("user", "a.read"));

        assertThrows(UnsupportedOperationException.class, () -> mapper.permissionsOf("user").add("evil"));
    }
}
