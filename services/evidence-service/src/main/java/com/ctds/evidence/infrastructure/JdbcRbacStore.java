package com.ctds.evidence.infrastructure;

import com.ctds.evidence.domain.RbacChangeView;
import com.ctds.evidence.domain.RbacStore;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * WBS-3.9.2 §3 最小集：映射表与变更留痕表的本地读写。
 * 授予 / 收回与留痕由调用方（RbacPermissionService）以同一事务包住，唯一键保证幂等。
 */
@Repository
public class JdbcRbacStore implements RbacStore {

    private final JdbcTemplate jdbc;

    public JdbcRbacStore(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全量生效映射（R1 读面与值域封闭校验的共同来源）。 */
    public Map<String, Set<String>> allRolePermissions() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        List<Map.Entry<String, String>> rows = jdbc.query(
                "SELECT role_code, permission_point FROM role_permission WHERE enabled = 1",
                (rs, rowNum) -> Map.entry(rs.getString(1), rs.getString(2)));
        for (Map.Entry<String, String> row : rows) {
            result.computeIfAbsent(row.getKey(), key -> new LinkedHashSet<>()).add(row.getValue());
        }
        return result;
    }

    /** 重复变更单号 / 重复授予 = 零新留痕（幂等）。 */
    public boolean grant(final String role, final String permission, final String changeNo,
            final String changeOrderNo, final String changedBy, final Timestamp changedAt) {
        if (replayed(role, permission, changeOrderNo)) {
            return false;
        }
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission "
                        + "WHERE role_code = ? AND permission_point = ? AND enabled = 1",
                Integer.class, role, permission);
        if (existing != null && existing > 0) {
            return false;
        }
        jdbc.update("INSERT INTO role_permission "
                        + "(role_code, permission_point, enabled, version_no) VALUES (?, ?, 1, 1) "
                        + "ON DUPLICATE KEY UPDATE enabled = 1, version_no = version_no + 1",
                role, permission);
        insertChange(changeNo, changeOrderNo, changedBy, changedAt,
                role, permission, "0", "1");
        return true;
    }

    /** 收回未授予的点 = 零副作用；返回 false 由上层转业务拒绝。 */
    public boolean revoke(final String role, final String permission, final String changeNo,
            final String changeOrderNo, final String changedBy, final Timestamp changedAt) {
        if (replayed(role, permission, changeOrderNo)) {
            return false;
        }
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission "
                        + "WHERE role_code = ? AND permission_point = ? AND enabled = 1",
                Integer.class, role, permission);
        if (existing == null || existing == 0) {
            return false;
        }
        jdbc.update("UPDATE role_permission SET enabled = 0, version_no = version_no + 1 "
                + "WHERE role_code = ? AND permission_point = ?", role, permission);
        insertChange(changeNo, changeOrderNo, changedBy, changedAt,
                role, permission, "1", "0");
        return true;
    }

    /** 留痕读面：按时间倒序分页，字段齐备（B1-R4）。 */
    public List<RbacChangeView> changes(final long offset, final int limit) {
        return jdbc.query("SELECT change_no, change_order_no, changed_by, "
                        + "CAST(changed_at AS CHAR), role_code, permission_point, "
                        + "before_value, after_value FROM role_permission_change_log "
                        + "ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new RbacChangeView(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8)),
                limit, offset);
    }

    public long countChanges() {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission_change_log", Long.class);
        return total == null ? 0L : total;
    }

    private boolean replayed(final String role, final String permission,
            final String changeOrderNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission_change_log "
                        + "WHERE role_code = ? AND permission_point = ? AND change_order_no = ?",
                Integer.class, role, permission, changeOrderNo);
        return count != null && count > 0;
    }

    private void insertChange(final String changeNo, final String changeOrderNo,
            final String changedBy, final Timestamp changedAt, final String role,
            final String permission, final String before, final String after) {
        jdbc.update("INSERT INTO role_permission_change_log "
                        + "(change_no, change_order_no, changed_by, changed_at, role_code, "
                        + "permission_point, before_value, after_value) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                changeNo, changeOrderNo, changedBy, changedAt, role, permission, before, after);
    }
}
