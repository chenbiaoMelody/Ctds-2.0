package com.ctds.contract.interfaces.dto;

/**
 * 修订响应视图（hifi §2.1 W2 契约：模板号 + 新版本号 + 修订前版本号——版本快照链路可读）。
 */
public record RevisionView(String templateNo, int versionNo, int previousVersionNo) {

    /** 领域 → 出站映射（previousVersionNo = 新版本号 − 1，行级版本化递增保证）。 */
    public static RevisionView from(final String templateNo, final int versionNo) {
        return new RevisionView(templateNo, versionNo, versionNo - 1);
    }
}
