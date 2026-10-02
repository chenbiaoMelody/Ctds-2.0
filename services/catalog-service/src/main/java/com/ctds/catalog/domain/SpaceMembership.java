package com.ctds.catalog.domain;

/**
 * 空间成员关系判定结果（WBS-3.3.2 hifi §1.2 / §5；单一事实源在 space-service，
 * 经其内部端点最小暴露两字段语义——spaceStatus + role|NONE，不回空间名称/详情）。
 *
 * <p>三态：可用判定（spaceStatus=NONE 表示空间不存在——防枚举同形，登记判 1007C0002、
 * 读面判 1007C0005，不区分"空间不存在"与"无权"）；UNAVAILABLE = 空间服务不可达/失败
 * （1007S0002，不冒充"非成员/空间不存在"——hifi §5 client 契约）。</p>
 *
 * @param available   判定是否可用（false = 空间服务不可达/失败）
 * @param spaceStatus 空间状态：CREATED/ACTIVE/FROZEN/DISSOLVED/NONE（NONE = 空间不存在）
 * @param role        调用主体在该空间的角色：OWNER/ADMIN/MEMBER/NONE（NONE = 非成员或成员行终态）
 */
public record SpaceMembership(boolean available, String spaceStatus, String role) {

    /** 空间不存在（space 端点防枚举同形答复）。 */
    public static final String STATUS_NONE = "NONE";
    /** 非成员。 */
    public static final String ROLE_NONE = "NONE";
    /** 已解散空间状态名（WBS-3.3.5 上收：产品封装/上架的"空间未解散"门槛共用判定值——
     * 与 space 域 SpaceStatus 枚举名对齐；沿用 "DISSOLVED" 字面值域由本行注释锚定）。 */
    public static final String SPACE_STATUS_DISSOLVED = "DISSOLVED";

    /** 空间服务不可用（统一 UNAVAILABLE 语义）。 */
    public static SpaceMembership unavailable() {
        return new SpaceMembership(false, STATUS_NONE, ROLE_NONE);
    }

    /** 可用判定（spaceStatus/role 为 space 端点原始答复）。 */
    public static SpaceMembership of(final String spaceStatus, final String role) {
        return new SpaceMembership(true, spaceStatus, role);
    }

    /** 是否空间成员（member 及以上——登记门槛，行为 1 规则 1）。 */
    public boolean isMember() {
        return available && !ROLE_NONE.equals(role);
    }

    /** 空间是否为 ACTIVE（登记空间状态门槛，行为 1 规则 2；NONE/不可用 = false）。 */
    public boolean isSpaceActive() {
        return available && "ACTIVE".equals(spaceStatus);
    }
}
