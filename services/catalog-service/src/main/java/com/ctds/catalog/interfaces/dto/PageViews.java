package com.ctds.catalog.interfaces.dto;

import com.ctds.common.pagination.PageResult;
import java.util.function.Function;

/**
 * 分页映射助手（WBS-3.3.6 hifi §5；DB-39 Q6-A：服务内单点助手承载本卡 3 处新增端点的
 * {@code PageResult} 映射——新增面零复制；既有 7 处手工映射零改动，随 common 变更窗口统一收口）。
 */
public final class PageViews {

    private PageViews() {
    }

    /** 源分页 → 目标分页（逐页映射，分页字段原样透传）。 */
    public static <S, T> PageResult<T> page(final PageResult<S> source, final Function<S, T> mapper) {
        return new PageResult<>(source.list().stream().map(mapper).toList(),
                source.total(), source.pageNum(), source.pageSize(), source.totalPages());
    }
}
