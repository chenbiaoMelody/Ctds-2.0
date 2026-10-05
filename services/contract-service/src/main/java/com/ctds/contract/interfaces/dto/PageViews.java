package com.ctds.contract.interfaces.dto;

import com.ctds.common.pagination.PageResult;
import java.util.function.Function;

/**
 * 分页映射助手（沿 catalog PageViews 先例——DB-39 Q6-A：服务内单点助手承载分页映射）。
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
