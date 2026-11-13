package com.ctds.evidence.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.evidence.domain.RbacChangeView;
import com.ctds.evidence.domain.RbacErrorCodes;
import com.ctds.evidence.domain.RbacStore;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WBS-3.9.2 W1/W2/R1/R2 应用服务：授予 / 收回与变更留痕同事务；
 * 角色与权限点值域封闭（未登记一律拒绝，新增须走变更流程，规格 B1-R1 / B1-R2）。
 */
@Service
public class RbacPermissionService {

    private static final int MAX_LENGTH = 64;
    private static final String UNKNOWN_ACTOR = "unknown";

    private final RbacStore store;

    public RbacPermissionService(final RbacStore store) {
        this.store = store;
    }

    /** 授予：重复授予（同变更单号 / 已生效）为幂等成功，零新增留痕。 */
    @Transactional
    public String grant(final String role, final String permission,
            final String changeOrderNo, final String changedBy) {
        validate(role, permission, changeOrderNo);
        final String changeNo = newChangeNo();
        boolean applied = store.grant(role, permission, changeNo, changeOrderNo,
                normalizeActor(changedBy), new Timestamp(System.currentTimeMillis()));
        return applied ? changeNo : "";
    }

    /** 收回：未授予的点零副作用拒绝，不产生留痕。 */
    @Transactional
    public String revoke(final String role, final String permission,
            final String changeOrderNo, final String changedBy) {
        validate(role, permission, changeOrderNo);
        final String changeNo = newChangeNo();
        boolean applied = store.revoke(role, permission, changeNo, changeOrderNo,
                normalizeActor(changedBy), new Timestamp(System.currentTimeMillis()));
        if (!applied) {
            throw new BizException(RbacErrorCodes.REVOKE_NOT_GRANTED,
                    RbacErrorCodes.REVOKE_NOT_GRANTED_MESSAGE);
        }
        return changeNo;
    }

    /** 角色 → 权限点集合读面（未知角色为空集，不回退配置旧值）。 */
    public List<RbacRoleView> roles() {
        List<RbacRoleView> views = new ArrayList<>();
        store.allRolePermissions().forEach((role, points) ->
                views.add(new RbacRoleView(role, List.copyOf(points))));
        return views;
    }

    /** 变更留痕分页读面。 */
    public PageResult<RbacChangeView> changes(final Integer pageNum, final Integer pageSize) {
        PageQuery query = PageQuery.of(pageNum, pageSize, null);
        return PageResult.of(store.changes(query.offset(), query.pageSize()),
                store.countChanges(), query);
    }

    private void validate(final String role, final String permission,
            final String changeOrderNo) {
        if (isBlank(role) || isBlank(permission) || isBlank(changeOrderNo)
                || role.length() > MAX_LENGTH || permission.length() > MAX_LENGTH
                || changeOrderNo.length() > MAX_LENGTH) {
            throw new BizException(RbacErrorCodes.PARAM_INVALID,
                    RbacErrorCodes.PARAM_INVALID_MESSAGE);
        }
        Map<String, Set<String>> all = store.allRolePermissions();
        Set<String> points = new LinkedHashSet<>();
        all.values().forEach(points::addAll);
        if (!all.containsKey(role) || !points.contains(permission)) {
            throw new BizException(RbacErrorCodes.UNREGISTERED,
                    RbacErrorCodes.UNREGISTERED_MESSAGE);
        }
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeActor(final String actor) {
        if (isBlank(actor)) {
            return UNKNOWN_ACTOR;
        }
        return actor.length() > MAX_LENGTH ? actor.substring(0, MAX_LENGTH) : actor;
    }

    private static String newChangeNo() {
        return "RC" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
