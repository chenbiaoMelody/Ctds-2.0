package com.ctds.example.application;

import com.ctds.example.domain.DemoNote;
import com.ctds.example.domain.DemoNoteRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 应用服务：迁移演示笔记查询（WBS 2.4.10）。
 * 仅在 mysql profile（ctds.demo.db-enabled=true）装配——默认 profile 无数据库，行为与 2.4.9 合并时点一致。
 * 表结构由 Flyway 迁移脚本管理（ADR-009），本服务只读数据证明迁移真实生效。
 */
@Service
@ConditionalOnProperty(name = "ctds.demo.db-enabled", havingValue = "true")
public class DemoNoteService {

    private final DemoNoteRepository demoNoteRepository;

    public DemoNoteService(final DemoNoteRepository demoNoteRepository) {
        this.demoNoteRepository = demoNoteRepository;
    }

    public List<DemoNote> list() {
        return demoNoteRepository.findAll();
    }
}
