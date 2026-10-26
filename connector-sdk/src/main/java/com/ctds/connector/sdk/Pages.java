package com.ctds.connector.sdk;

import org.springframework.web.util.UriBuilder;

/**
 * 分页参数口径（对齐平台 {@code PageQuery}：pageNum 默认 1 / 上限 10000，pageSize 默认 10 / 范围 1~100）。
 *
 * <p>SDK 在本地完成范围校验（CONFIG fail-fast），不把非法分页参数打到平台。</p>
 */
final class Pages {

    /** 页号上限（平台 PageQuery.MAX_PAGE_NUM）。 */
    private static final int MAX_PAGE_NUM = 10000;

    /** 页大小上限（平台 PageQuery.MAX_PAGE_SIZE）。 */
    private static final int MAX_PAGE_SIZE = 100;

    private Pages() {
    }

    /** 校验分页参数并写入查询串（对平台 {@code /mine} 端点；返回入参 builder 便于链式构建）。 */
    static UriBuilder apply(UriBuilder uri, int pageNum, int pageSize) {
        if (pageNum < 1 || pageNum > MAX_PAGE_NUM) {
            throw new ConnectorException(SdkErrorType.CONFIG,
                    "pageNum 必须在 1~" + MAX_PAGE_NUM + " 之间", null);
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new ConnectorException(SdkErrorType.CONFIG,
                    "pageSize 必须在 1~" + MAX_PAGE_SIZE + " 之间", null);
        }
        return uri.queryParam("pageNum", pageNum).queryParam("pageSize", pageSize);
    }
}
