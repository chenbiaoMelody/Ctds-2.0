package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 数据集视图。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DatasetView(Long datasetId, String name, String status) {
}
