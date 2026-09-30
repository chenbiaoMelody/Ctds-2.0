package com.ctds.catalog.domain;

import java.time.LocalDateTime;

/**
 * 数据资源（一行 = 一个数据资源；WBS-3.3.2 hifi §3.1，逐列对应 dataset 表）。
 *
 * <p>资源是登记与元数据载体，不承载本体数据的存储与传输（规格术语表）；数据标识 data_no
 * 平台生成、全平台唯一、不可变；语义标签以 JSON 数组字符串入库存载体（{@link SemanticTags}）。</p>
 */
public record Dataset(
        Long id,
        String dataNo,
        long spaceId,
        String ownerSubjectNo,
        String name,
        String normalizedName,
        DatasetType type,
        String intro,
        String semanticTagsJson,
        String declareCategory,
        DeclareLevel declareLevel,
        boolean declareImportant,
        DatasetStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
