package com.ctds.catalog.application;

import com.ctds.catalog.domain.DatasetType;
import com.ctds.catalog.domain.DeclareLevel;
import java.util.List;

/**
 * 登记命令载荷（WBS-3.3.2 hifi §4.2；由接口层 DTO 白名单映射后传入应用服务）。
 * 归一化名由命令服务先行计算后填入（幂等键组成项之一）。
 *
 * @param spaceId          目标空间 id（路径参数）
 * @param ownerSubjectNo   登记主体编号（AuthContext 取用，不收请求体传入）
 * @param name             资源名称（原始输入）
 * @param normalizedName   归一化名称（命令服务计算）
 * @param type             资源类型（受控枚举）
 * @param intro            简介
 * @param semanticTags     语义标签（载体级去重后；JSON 化在应用服务）
 * @param declareCategory  分类申报（字符串载体）
 * @param declareLevel     分级申报
 * @param declareImportant 重要数据申报（true → 代码强制拒收）
 */
public record CreateDatasetCommand(
        long spaceId,
        String ownerSubjectNo,
        String name,
        String normalizedName,
        DatasetType type,
        String intro,
        List<String> semanticTags,
        String declareCategory,
        DeclareLevel declareLevel,
        boolean declareImportant) {
}
