package com.ctds.space.interfaces.dto;

import com.ctds.space.domain.Space;
import com.ctds.space.domain.SpaceMember;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 空间摘要视图（WBS-3.2.3 hifi §1 端点 7 列表出参 + 端点 8 非成员公开摘要）：不含成员构成
 * 与所有者标识（非成员可见面最小化）。
 */
public record SpaceSummaryView(
        Long id,
        String name,
        String sceneType,
        String accessMode,
        String visibility,
        String intro,
        String status,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo) {

    public static SpaceSummaryView from(final Space space) {
        return new SpaceSummaryView(space.id(), space.name(), space.sceneType().name(),
                space.accessMode().name(), space.visibility().name(), space.intro(), space.status().name(),
                space.effectiveFrom(), space.effectiveTo());
    }

    /** 空间详情视图（全量：成员/owner/admin/运营方可见，含成员构成）。 */
    public record SpaceDetailView(
            Long id,
            String name,
            String sceneType,
            String accessMode,
            String visibility,
            String intro,
            LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo,
            String ownerSubjectNo,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<MemberView> members) {

        public static SpaceDetailView from(final Space space, final List<SpaceMember> members) {
            return new SpaceDetailView(space.id(), space.name(), space.sceneType().name(),
                    space.accessMode().name(), space.visibility().name(), space.intro(),
                    space.effectiveFrom(), space.effectiveTo(), space.ownerSubjectNo(), space.status().name(),
                    space.createdAt(), space.updatedAt(),
                    members.stream().map(MemberView::from).toList());
        }
    }

    /** 成员构成视图（仅活跃成员；角色迁移与历史关系追溯归 3.2.4 读面）。 */
    public record MemberView(String subjectNo, String role) {

        public static MemberView from(final SpaceMember member) {
            return new MemberView(member.subjectNo(), member.role().name());
        }
    }
}
