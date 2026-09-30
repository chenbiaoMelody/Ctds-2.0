package com.ctds.catalog.domain;

/**
 * 空间成员关系只读端口（WBS-3.3.2 hifi §5；实现 = infrastructure.SpaceMembershipClient，
 * 目标 = space 内部端点 `GET /api/v1/data-spaces/internal/{spaceId}/memberships/{subjectNo}`
 * ——本卡新增，Q2-A：既有服务唯一改动，最小暴露 spaceStatus+role|NONE）。
 */
public interface SpaceMembershipPort {

    /** 判定主体在目标空间的成员关系与空间状态（不可达/失败 = UNAVAILABLE 语义由实现收敛）。 */
    SpaceMembership check(long spaceId, String subjectNo);
}
