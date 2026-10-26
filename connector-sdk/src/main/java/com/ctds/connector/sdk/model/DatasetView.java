package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 数据集视图（对齐平台 DatasetView 核心字段：id / name / type / intro）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DatasetView(Long id, String name, String type, String intro) {
}
