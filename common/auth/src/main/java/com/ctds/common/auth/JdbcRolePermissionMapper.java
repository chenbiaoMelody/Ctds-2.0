package com.ctds.common.auth;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WBS-3.9.2 Q1-A：角色 → 权限点映射的库表来源，判定内核（DefaultAccessControl）零改动。
 * 只读跨 schema SELECT；库不可达 / 读失败一律 fail-closed 返回空集，绝不回退配置文件旧值。
 */
public class JdbcRolePermissionMapper implements RolePermissionMapper {

    private static final Logger log = LoggerFactory.getLogger(JdbcRolePermissionMapper.class);
    private static final Pattern SAFE_SCHEMA = Pattern.compile("[A-Za-z0-9_]+");
    private static final String SELECT = "SELECT permission_point FROM %s.role_permission "
            + "WHERE role_code = ? AND enabled = 1";

    private final JdbcTemplate jdbcTemplate;
    private final String sql;

    public JdbcRolePermissionMapper(final JdbcTemplate jdbcTemplate, final String schema) {
        if (jdbcTemplate == null || schema == null || !SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("invalid rbac schema or missing JdbcTemplate");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.sql = String.format(SELECT, schema);
    }

    @Override
    public Set<String> permissionsOf(final String role) {
        if (role == null || role.isBlank()) {
            return Set.of();
        }
        try {
            return new LinkedHashSet<>(
                    jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString(1), role));
        } catch (DataAccessException ex) {
            log.warn("rbac mapping unavailable: fail-closed (empty permission set)", ex);
            return Set.of();
        }
    }
}
