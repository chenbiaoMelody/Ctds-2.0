package com.ctds.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WBS-3.9.2 T8：库表映射的未知角色空集、读失败 fail-closed、schema 白名单。
 * 用真实 JdbcTemplate + 桩 DataSource 走完整调用链，不 mock 泛型重载。
 */
class JdbcRolePermissionMapperTest {

    @Test
    void unknownRoleReturnsEmptySetWithoutTouchingDatabase() {
        DataSource dataSource = mock(DataSource.class);
        JdbcRolePermissionMapper mapper =
                new JdbcRolePermissionMapper(new JdbcTemplate(dataSource), "ctds_evidence");
        assertThat(mapper.permissionsOf(null)).isEmpty();
        assertThat(mapper.permissionsOf("  ")).isEmpty();
        verifyNoInteractions(dataSource);
    }

    @Test
    void databaseFailureFailsClosedWithEmptySet() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("mapping store unavailable"));
        JdbcRolePermissionMapper mapper =
                new JdbcRolePermissionMapper(new JdbcTemplate(dataSource), "ctds_evidence");
        assertThat(mapper.permissionsOf("platform.secadmin")).isEmpty();
    }

    @Test
    void unsafeSchemaIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new JdbcRolePermissionMapper(
                new JdbcTemplate(mock(DataSource.class)), "ctds_evidence; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
