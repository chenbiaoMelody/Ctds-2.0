package com.ctds.space.application;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.space.domain.Space;
import com.ctds.space.domain.SpaceBizException;
import com.ctds.space.domain.SpaceErrorCodes;
import com.ctds.space.domain.SpaceMember;
import com.ctds.space.domain.SpaceNameNormalizer;
import com.ctds.space.domain.SpaceRepository;
import com.ctds.space.domain.Visibility;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 空间读面服务（WBS-3.2.3 hifi §1 端点 7/8，行为 6 规则 3"可见性=可否被检索"承载）：
 * 列表 = 登录主体仅 PUBLIC + platform.operator 全量（终态一律不出现在可检索面，hifi §8）；
 * 详情 = 成员/owner/admin/运营方全量（含成员构成），非成员仅 PUBLIC 摘要（不含成员构成），
 * 不公开空间对非成员不可见（1006C0004 不区分存在性，防枚举）。
 */
@Service
public class SpaceQueryService {

    private final SpaceRepository repository;
    private final SpaceAccessGuard guard;

    public SpaceQueryService(final SpaceRepository repository, final SpaceAccessGuard guard) {
        this.repository = repository;
        this.guard = guard;
    }

    /**
     * 检索列表：keyword 按归一化名称前缀匹配（语义登记：检索按名称前缀，防全表扫）；
     * 纯空白/控制字符的 keyword 归一化为空前缀 = 不限关键词。
     */
    public PageResult<Space> list(final Integer pageNum, final Integer pageSize, final String keyword) {
        guard.requireSubject();
        String prefix = null;
        if (keyword != null && !keyword.isBlank()) {
            final String normalized = SpaceNameNormalizer.normalize(keyword);
            if (!normalized.isEmpty()) {
                prefix = normalized;
            }
        }
        return repository.search(guard.isPlatformOperator(), prefix, PageQuery.of(pageNum, pageSize, null));
    }

    /**
     * 详情：全量（含成员构成）= 成员/owner/admin 或 platform.operator；非成员仅 PUBLIC 摘要；
     * 不公开空间对非成员 = 1006C0004（可见性=不可见，不区分存在性）。
     */
    public SpaceView detail(final long spaceId) {
        final String subject = guard.requireSubject();
        final Space space = repository.findById(spaceId)
                .orElseThrow(() -> notFound());
        if (guard.isPlatformOperator() || guard.isOwner(space)) {
            return new SpaceView(space, repository.findActiveMembers(spaceId), true);
        }
        final List<SpaceMember> members = repository.findActiveMembers(spaceId);
        final boolean isMember = subject != null && members.stream()
                .anyMatch(member -> subject.equals(member.subjectNo()));
        if (isMember) {
            return new SpaceView(space, members, true);
        }
        if (space.visibility() == Visibility.PUBLIC) {
            return new SpaceView(space, List.of(), false);
        }
        throw notFound();
    }

    /** 空间不存在/不可见（统一 1006C0004，读面防枚举）。 */
    private static SpaceBizException notFound() {
        return new SpaceBizException(SpaceErrorCodes.SPACE_NOT_FOUND, SpaceErrorCodes.SPACE_NOT_FOUND_MESSAGE);
    }

    /** 读面视图：fullDetail=true 全量（含成员构成）；false = 非成员公开摘要（不含成员构成）。 */
    public record SpaceView(Space space, List<SpaceMember> members, boolean fullDetail) {
    }
}
