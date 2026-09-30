package com.ctds.space.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.space.domain.Space;
import com.ctds.space.domain.SpaceMember;
import com.ctds.space.domain.SpaceRepository;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部只读端点（WBS-3.3.2 hifi §1.2 本卡对既有空间的唯一改动，Q2-A；沿 subject
 * InternalAdmissionController 先例）：供目录服务判定"空间状态 + 调用主体成员角色"。
 *
 * <p><b>最小暴露</b>：只回判定所需两字段语义（spaceStatus + role|NONE），不回空间名称/详情/成员列表
 * ——避免内部面变业务面；功能门槛 = 服务身份专用权限点 {@code space.internal.read}
 * （仅授予 catalog-internal，业务角色不持有，防空间状态与成员关系枚举）；
 * 空间不存在 → spaceStatus=NONE 同形表达（防枚举，ADR-016 §6 口径）；
 * 不落归属断言与查看留痕（内部只读面，诚实边界沿 subject 内部端点登记）。</p>
 */
@RestController
@RequestMapping("/api/v1/data-spaces/internal")
public class InternalMembershipController {

    /** 空间不存在（防枚举同形表达）。 */
    private static final String STATUS_NONE = "NONE";
    /** 非成员（成员行不存在或已终态）。 */
    private static final String ROLE_NONE = "NONE";

    private final SpaceRepository repository;

    public InternalMembershipController(final SpaceRepository repository) {
        this.repository = repository;
    }

    /** 成员关系与空间状态（仅 spaceStatus + role 两字段；空间不存在 → NONE/NONE 同形）。 */
    @GetMapping(path = "/{spaceId}/memberships/{subjectNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("space.internal.read")
    public ApiResult<MembershipView> membership(@PathVariable final long spaceId,
            @PathVariable final String subjectNo) {
        final Optional<Space> space = repository.findById(spaceId);
        if (space.isEmpty()) {
            return ApiResult.ok(new MembershipView(STATUS_NONE, ROLE_NONE));
        }
        final String role = repository.findActiveMembers(spaceId).stream()
                .filter(member -> subjectNo.equals(member.subjectNo()))
                .map(SpaceMember::role)
                .map(Enum::name)
                .findFirst()
                .orElse(ROLE_NONE);
        return ApiResult.ok(new MembershipView(space.get().status().name(), role));
    }

    /** 成员关系视图（内部面最小暴露：无空间名称、无成员列表、无详情字段）。 */
    public record MembershipView(String spaceStatus, String role) {
    }
}
