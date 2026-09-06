package com.ctds.common.pagination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageResultTest {

    @Test
    void ofShouldComputeTotalPages() {
        final PageQuery query = PageQuery.of(2, 10, null);

        final PageResult<String> result = PageResult.of(List.of("a", "b"), 25, query);

        assertEquals(2, result.list().size());
        assertEquals(25, result.total());
        assertEquals(2, result.pageNum());
        assertEquals(3, result.totalPages());
    }

    @Test
    void emptyShouldReturnZeroTotals() {
        final PageResult<String> result = PageResult.empty(PageQuery.of(1, 10, null));

        assertEquals(0, result.list().size());
        assertEquals(0, result.total());
        assertEquals(0, result.totalPages());
    }

    @Test
    void listShouldBeDefensivelyCopied() {
        final PageQuery query = PageQuery.of(1, 10, null);

        final PageResult<String> result = PageResult.of(new ArrayList<>(List.of("a")), 1, query);

        assertEquals(List.of("a"), result.list());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> result.list().add("b"));
    }
}
