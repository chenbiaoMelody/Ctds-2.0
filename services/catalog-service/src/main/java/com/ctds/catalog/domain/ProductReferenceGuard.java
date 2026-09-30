package com.ctds.catalog.domain;

/**
 * 注销引用保护挂点（WBS-3.3.2 hifi §4.4，Q4-A）：是否存在未注销产品引用该资源。
 *
 * <p><b>移交义务登记</b>：本卡（3.3.2）实现恒 false（产品表归 3.3.5，不存在时无从查起）——
 * 行为已接线（W3 注销前置检查）；3.3.5 产品表落地后须替换为实体检查实现并补测
 * C-3.1 剧本 S3 步骤 4"已引用资源不得注销"用例（拒绝错误码随 3.3.5 定）。</p>
 */
public interface ProductReferenceGuard {

    /** 是否存在未注销产品引用；本卡实现恒 false（3.3.5 替换为实体检查）。 */
    boolean hasActiveProductReferences(long datasetId);
}
