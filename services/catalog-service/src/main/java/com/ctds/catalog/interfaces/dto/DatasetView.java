package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.Dataset;
import com.ctds.catalog.domain.SemanticTags;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 资源视图（WBS-3.3.2 hifi §1.1：DatasetView）。全部字段均为目录元数据，
 * <b>不含数据本体</b>（规格行为 7 规则 5——资源是登记与元数据载体，不承载本体数据）。
 * tags 以 JSON 数组出站（列表形态），入库存载体为 JSON 字符串。
 */
public record DatasetView(
        long id,
        String dataNo,
        long spaceId,
        String name,
        String type,
        String intro,
        List<String> tags,
        String declareCategory,
        String declareLevel,
        boolean declareImportant,
        String status,
        LocalDateTime createdAt) {

    /** 由领域对象映射视图（语义标签 JSON 载体 → 列表出站）。 */
    public static DatasetView from(final Dataset dataset) {
        return new DatasetView(dataset.id(), dataset.dataNo(), dataset.spaceId(), dataset.name(),
                dataset.type().name(), dataset.intro(), SemanticTags.fromJson(dataset.semanticTagsJson()),
                dataset.declareCategory(), dataset.declareLevel().name(), dataset.declareImportant(),
                dataset.status().name(), dataset.createdAt());
    }
}
