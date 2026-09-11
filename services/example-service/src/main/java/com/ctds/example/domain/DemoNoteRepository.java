package com.ctds.example.domain;

import java.util.List;

/**
 * 领域仓储接口：迁移演示笔记（WBS 2.4.10）。实现随 mysql profile 提供（基础设施层 JdbcTemplate），
 * 默认 profile 无实现、演示端点不装配——应用层仅依赖本接口。
 */
public interface DemoNoteRepository {

    List<DemoNote> findAll();
}
