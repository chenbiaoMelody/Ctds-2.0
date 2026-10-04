package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.ProductActionLogRow;
import java.time.LocalDateTime;

/**
 * 产品操作留痕视图（WBS-3.3.6 hifi §1.2 R15 出参；<b>全值域</b>——含 DENIED_* 拒绝留痕与
 * GOVERNANCE_VIEW 治理查看留痕，与 R8 订阅者可见值域形成对照；读面 = 提供方本人 + 治理例外 admin）。
 */
public record ProductActionLogView(Long id, String action, String operatorSubjectNo, String summary,
        LocalDateTime createdAt) {

    /** 行 → 视图映射。 */
    public static ProductActionLogView from(final ProductActionLogRow row) {
        return new ProductActionLogView(row.id(), row.action(), row.operatorSubjectNo(), row.summary(),
                row.createdAt());
    }
}
