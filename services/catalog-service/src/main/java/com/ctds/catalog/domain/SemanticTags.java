package com.ctds.catalog.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 语义标签 JSON 载体（WBS-3.3.2 hifi §3.1：semantic_tags 列 = JSON 数组字符串，载体级）。
 * 受控词表成员校验已由 3.3.3 承接（{@code DatasetCommandService} 经 {@link TagTermPort}
 * 做归一化差集校验，载体形态不变——Q2-A）；本类只做载体级序列化/反序列化与基础校验
 * （必填、非空、数量 1~10、单标签 1~32、去重——Q7-A）。
 */
public final class SemanticTags {

    /** 标签数量上限（Q7-A）。 */
    public static final int MAX_COUNT = 10;
    /** 单标签长度上限（Q7-A）。 */
    public static final int MAX_LENGTH = 32;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SemanticTags() {
    }

    /** 序列化为 JSON 数组字符串（入库存载体；保持传入顺序）。 */
    public static String toJson(final List<String> tags) {
        try {
            return MAPPER.writeValueAsString(tags);
        } catch (final JsonProcessingException e) {
            // List<String> 序列化无可失败路径；兜底为非法参数（编码缺陷快速暴露）
            throw new IllegalArgumentException("语义标签序列化失败", e);
        }
    }

    /** 从 JSON 数组字符串解析（出库读面）；空串/空白 = 空列表。 */
    public static List<String> fromJson(final String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (final JsonProcessingException e) {
            // 入库值均经 toJson 产出；解析失败 = 存储被污染，快速暴露
            throw new IllegalStateException("语义标签载体解析失败", e);
        }
    }

    /**
     * 载体级校验（Q7-A）：非 null、1~10 项、逐项非空白且 ≤32 字符、去重后数量不变。
     *
     * @return 校验问题清单（空 = 通过；逐条业务文案）
     */
    public static List<String> validate(final List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of("语义标签不能为空");
        }
        if (tags.size() > MAX_COUNT) {
            return List.of("语义标签数量超限（最多 " + MAX_COUNT + " 项）");
        }
        for (final String tag : tags) {
            if (tag == null || tag.isBlank()) {
                return List.of("语义标签不能为空白");
            }
            if (tag.length() > MAX_LENGTH) {
                return List.of("单个语义标签超长（≤" + MAX_LENGTH + " 字符）");
            }
        }
        final Set<String> distinct = new LinkedHashSet<>(tags);
        if (distinct.size() != tags.size()) {
            return List.of("语义标签存在重复项");
        }
        return List.of();
    }
}
