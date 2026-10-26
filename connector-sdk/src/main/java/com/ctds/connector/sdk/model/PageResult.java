package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 分页信封（同构平台 common-pagination PageResult：三个"我的…"查询端点均返回本结构）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageResult<T>(List<T> list, Long total, Integer pageNum, Integer pageSize, Integer totalPages) {
}
