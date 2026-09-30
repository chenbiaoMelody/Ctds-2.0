package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.application.DatasetCommandService;
import com.ctds.catalog.domain.DeclareLevel;
import java.util.List;

/**
 * 变更请求体（WBS-3.3.2 hifi §1.1 W2）：可变字段白名单 = 简介/语义标签/分类申报/分级申报
 * （名称与类型不可变更——资源标识与形态稳定，规格行为 2 规则 1 可变字段集口径）；
 * 字段缺省 = 不变更该项；全缺省 → 应用服务 400（无可变更项）。
 * 出站/入站字段名 = {@code tags}（hifi §1.1 契约口径）；内部载体名 = 语义标签（semantic_tags 列）。
 */
public record UpdateDatasetRequest(
        String intro,
        List<String> tags,
        String declareCategory,
        DeclareLevel declareLevel) {

    /** 映射为应用层变更载荷。 */
    public DatasetCommandService.DatasetUpdateRequest toCommand() {
        return new DatasetCommandService.DatasetUpdateRequest(intro, tags, declareCategory,
                declareLevel);
    }
}
