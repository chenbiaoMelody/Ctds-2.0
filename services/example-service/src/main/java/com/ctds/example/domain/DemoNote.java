package com.ctds.example.domain;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import java.time.LocalDateTime;

/**
 * 领域实体：迁移演示笔记（WBS 2.4.10）。表结构由 Flyway 迁移脚本管理（ADR-009），本实体不感知迁移工具。
 */
public record DemoNote(Long id, String title, String content, LocalDateTime createdAt) {

    public DemoNote {
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "title must not be null or blank");
        }
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "content must not be null or blank");
        }
    }
}
