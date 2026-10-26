package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 产品视图。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductView(Long productId, String productName, String status) {
}
