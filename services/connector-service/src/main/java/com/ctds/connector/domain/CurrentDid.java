package com.ctds.connector.domain;

/**
 * 主体当前 DID 视图（DID 域内部只读面返回）。
 * status ∈ ACTIVE / REVOKED / PENDING_ISSUE / NONE（未签发）/ UNAVAILABLE（不可判定）。
 * 不可判定与"未签发"语义严格区分：前者必须 503，不得当作核验结论。
 */
public record CurrentDid(String did, String status) {

    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";
    public static final String PENDING_ISSUE = "PENDING_ISSUE";
    public static final String NONE = "NONE";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    public static CurrentDid unavailable() {
        return new CurrentDid(null, UNAVAILABLE);
    }

    public boolean isUnavailable() {
        return UNAVAILABLE.equals(status);
    }

    public boolean active() {
        return ACTIVE.equals(status);
    }
}
