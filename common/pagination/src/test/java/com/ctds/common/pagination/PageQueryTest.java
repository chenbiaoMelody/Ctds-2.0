package com.ctds.common.pagination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ctds.common.errorcode.BizException;
import org.junit.jupiter.api.Test;

class PageQueryTest {

    @Test
    void ofShouldApplyDefaultsWhenParamsNull() {
        final PageQuery query = PageQuery.of(null, null, null);

        assertEquals(1, query.pageNum());
        assertEquals(10, query.pageSize());
        assertNull(query.sortField());
        assertNull(query.sortDirection());
        assertEquals(0, query.offset());
    }

    @Test
    void ofShouldParseOrderByWithDefaultAsc() {
        final PageQuery query = PageQuery.of(2, 20, "created_at,DESC");

        assertEquals("created_at", query.sortField());
        assertEquals("desc", query.sortDirection());
        assertEquals(20, query.offset());
    }

    @Test
    void ofShouldDefaultToAscWhenDirectionOmitted() {
        final PageQuery query = PageQuery.of(1, 10, "message");

        assertEquals("message", query.sortField());
        assertEquals("asc", query.sortDirection());
    }

    @Test
    void ofShouldRejectOutOfRangePageParams() {
        final BizException thrown = assertThrows(BizException.class, () -> PageQuery.of(0, 10, null));
        assertEquals("1000C0001", thrown.getErrorCode().value());
        assertThrows(BizException.class, () -> PageQuery.of(-1, 10, null));
        assertThrows(BizException.class, () -> PageQuery.of(1, 0, null));
        assertThrows(BizException.class, () -> PageQuery.of(1, 101, null));
        assertEquals(1, PageQuery.of(1, 1, null).pageSize());
    }

    @Test
    void ofShouldRejectIllegalOrderBy() {
        assertThrows(BizException.class, () -> PageQuery.of(1, 10, "drop table users"));
        assertThrows(BizException.class, () -> PageQuery.of(1, 10, "name;delete"));
        assertThrows(BizException.class, () -> PageQuery.of(1, 10, "name,up"));
        assertThrows(BizException.class, () -> PageQuery.of(1, 10, "name,asc,desc"));
    }

    @Test
    void ofShouldAcceptMaxPageSize() {
        assertEquals(100, PageQuery.of(1, 100, null).pageSize());
    }

    @Test
    void ofShouldRejectOverlargePageNumAndOrderBy() {
        assertThrows(BizException.class, () -> PageQuery.of(Integer.MAX_VALUE, 100, null));
        assertThrows(BizException.class, () -> PageQuery.of(10001, 100, null));
        assertThrows(BizException.class, () -> PageQuery.of(1, 10, "a".repeat(65)));
    }

    @Test
    void offsetShouldNotOverflow() {
        assertEquals(999900L, PageQuery.of(10000, 100, null).offset());
    }
}
