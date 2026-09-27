package com.ctds.space.interfaces.dto;

import com.ctds.space.domain.SpaceMember;
import java.time.LocalDateTime;

/**
 * 成员视图（WBS-3.2.4 hifi §1 端点 7/8/9/10/11 出参；含终态行回显——退出/移除后的行级结果）。
 */
public record MemberView(
        Long id,
        Long spaceId,
        String subjectNo,
        String role,
        String status,
        LocalDateTime joinedAt,
        LocalDateTime exitedAt) {

    public static MemberView from(final SpaceMember member) {
        return new MemberView(member.id(), member.spaceId(), member.subjectNo(), member.role().name(),
                member.status().name(), member.joinedAt(), member.exitedAt());
    }
}
