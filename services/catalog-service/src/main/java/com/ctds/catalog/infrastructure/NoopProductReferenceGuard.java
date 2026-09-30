package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.ProductReferenceGuard;
import org.springframework.stereotype.Component;

/**
 * 引用保护恒放行实现（WBS-3.3.2 hifi §4.4 Q4-A）：产品表归 3.3.5，产品不存在期间
 * 无从查起——恒 false（无未注销产品引用）= 注销前置检查放行，行为接线就位。
 *
 * <p><b>3.3.5 移交义务</b>：产品表落地后本类须替换为实体检查实现（查未注销产品引用），
 * 并补测 C-3.1 剧本 S3 步骤 4"存在未注销产品引用的资源不得注销"；拒绝错误码随 3.3.5 定。</p>
 */
@Component
public class NoopProductReferenceGuard implements ProductReferenceGuard {

    @Override
    public boolean hasActiveProductReferences(final long datasetId) {
        return false;
    }
}
