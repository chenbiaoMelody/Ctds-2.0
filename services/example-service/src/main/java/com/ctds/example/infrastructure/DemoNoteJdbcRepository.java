package com.ctds.example.infrastructure;

import com.ctds.example.domain.DemoNote;
import com.ctds.example.domain.DemoNoteRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基础设施层：迁移演示笔记 JDBC 仓储（WBS 2.4.10，mysql profile 装配）。
 * 只读 demo_note 表证明 Flyway 迁移真实生效（ADR-009）；正式 ORM（MyBatis-Plus）随首个业务包引入。
 */
@Repository
@ConditionalOnProperty(name = "ctds.demo.db-enabled", havingValue = "true")
public class DemoNoteJdbcRepository implements DemoNoteRepository {

    private final JdbcTemplate jdbcTemplate;

    public DemoNoteJdbcRepository(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<DemoNote> findAll() {
        return jdbcTemplate.query(
                "SELECT id, title, content, created_at FROM demo_note ORDER BY id ASC",
                (rs, rowNum) -> new DemoNote(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()));
    }
}
