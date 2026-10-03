package com.ctds.catalog.domain;

import java.time.LocalDateTime;

/**
 * 产品操作留痕行载体（WBS-3.3.6 R15 全值域读面；沿 {@link ProductChangeLogRow} 行载体先例——
 * R8 读面复用既有载体不变，本行载体服务全值域读面的 id 携带）。
 */
public record ProductActionLogRow(Long id, String action, String operatorSubjectNo, String summary,
        LocalDateTime createdAt) {
}
