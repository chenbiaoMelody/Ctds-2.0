package com.ctds.space.domain;

import java.time.LocalDateTime;

/**
 * 空间成员关系（一行 = 一条成员关系；WBS-3.2.2 hifi §1.3，逐列对应 space_member 表）。
 *
 * <p>退出/移除行保留改终态供追溯；同一空间同一主体至多一条生效关系、至多一个活跃所有者
 * 由 DB 层 uk_active_member / uk_active_owner 兜底（生成列不进模型——存储派生值非业务输入）。</p>
 */
public record SpaceMember(
        Long id,
        Long spaceId,
        String subjectNo,
        MemberRole role,
        MemberStatus status,
        LocalDateTime joinedAt,
        LocalDateTime exitedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
