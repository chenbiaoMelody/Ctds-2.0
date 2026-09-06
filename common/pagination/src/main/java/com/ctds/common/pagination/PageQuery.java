package com.ctds.common.pagination;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 分页查询参数（ADR-005 §3.2）：pageNum 从 1 起、上限 10000；pageSize 默认 10、上限 100；
 * orderBy 单字段最长 64 字符，格式 "字段名" 或 "字段名,asc|desc"（仅字母数字下划线，防注入；
 * 持久层仍须按实际列名白名单映射——接入时必须落地，本组件只做第一道格式闸门）。
 */
public final class PageQuery {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE_NUM = 10000;
    private static final int MAX_ORDER_BY_LENGTH = 64;
    private static final Pattern ORDER_BY = Pattern.compile("^[A-Za-z0-9_]+(,(asc|desc))?$", Pattern.CASE_INSENSITIVE);

    private final int pageNum;
    private final int pageSize;
    private final String sortField;
    private final String sortDirection;

    private PageQuery(final int pageNum, final int pageSize, final String sortField, final String sortDirection) {
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.sortField = sortField;
        this.sortDirection = sortDirection;
    }

    public static PageQuery of(final Integer pageNum, final Integer pageSize, final String orderBy) {
        final int pn = (pageNum == null) ? 1 : pageNum;
        final int ps = (pageSize == null) ? 10 : pageSize;
        if (pn < 1) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "pageNum 必须从 1 开始");
        }
        if (pn > MAX_PAGE_NUM) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "pageNum 过大");
        }
        if (ps < 1 || ps > MAX_PAGE_SIZE) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "pageSize 必须在 1~100 之间");
        }
        if (orderBy != null && orderBy.length() > MAX_ORDER_BY_LENGTH) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "orderBy 过长");
        }
        if (orderBy == null || orderBy.isBlank()) {
            return new PageQuery(pn, ps, null, null);
        }
        final String trimmed = orderBy.trim();
        if (!ORDER_BY.matcher(trimmed).matches()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "orderBy 格式非法");
        }
        final String[] parts = trimmed.split(",");
        final String direction = (parts.length == 2) ? parts[1].toLowerCase(Locale.ROOT) : "asc";
        return new PageQuery(pn, ps, parts[0], direction);
    }

    public int pageNum() {
        return pageNum;
    }

    public int pageSize() {
        return pageSize;
    }

    public String sortField() {
        return sortField;
    }

    public String sortDirection() {
        return sortDirection;
    }

    /** 供持久层计算偏移量：offset = (pageNum - 1) * pageSize，long 运算防溢出。 */
    public long offset() {
        return (long) (pageNum - 1) * pageSize;
    }
}
