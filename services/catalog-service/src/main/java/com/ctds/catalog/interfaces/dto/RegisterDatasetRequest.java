package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.application.CreateDatasetCommand;
import com.ctds.catalog.domain.DatasetType;
import com.ctds.catalog.domain.DeclareLevel;
import java.util.List;

/**
 * 登记请求体（WBS-3.3.2 hifi §1.1 W1）：要素齐备校验归应用服务（名称类问题 1007C0008、
 * 其余要素 400 通用参数码）；本 DTO 只做载荷映射，不含业务判定。
 * ownerSubjectNo 与 normalizedName 由应用服务填充（身份取 AuthContext、归一化服务端计算）。
 * 出站/入站字段名 = {@code tags}（hifi §1.1 契约口径）；内部载体名 = 语义标签（semantic_tags 列）。
 */
public record RegisterDatasetRequest(
        String name,
        DatasetType type,
        String intro,
        List<String> tags,
        String declareCategory,
        DeclareLevel declareLevel,
        Boolean declareImportant) {

    /** 映射为应用层命令（ownerSubjectNo / normalizedName 留空，由命令服务填充）。 */
    public CreateDatasetCommand toCommand() {
        return new CreateDatasetCommand(0L, null, name, null, type, intro, tags,
                declareCategory, declareLevel, Boolean.TRUE.equals(declareImportant));
    }
}
