package com.ctds.common.pagination;

import java.util.List;

/**
 * 分页响应（ADR-005 §3.2）：list 当前页数据、total 总条数、totalPages 总页数（total=0 时为 0）。
 * list 不可变（防御性拷贝）。
 */
public record PageResult<T>(List<T> list, long total, int pageNum, int pageSize, int totalPages) {

    public PageResult {
        list = (list == null) ? List.of() : List.copyOf(list);
    }

    public static <T> PageResult<T> of(final List<T> list, final long total, final PageQuery query) {
        return new PageResult<>(list, total, query.pageNum(), query.pageSize(), totalPages(total, query.pageSize()));
    }

    public static <T> PageResult<T> empty(final PageQuery query) {
        return of(List.of(), 0, query);
    }

    private static int totalPages(final long total, final int pageSize) {
        return (int) ((total + pageSize - 1) / pageSize);
    }
}
