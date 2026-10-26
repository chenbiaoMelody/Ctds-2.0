package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 主体登记详情视图（宽松映射，字段级对齐归 3.6.8 集成验证）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubjectRegistrationView(String subjectNo, String name, String status) {
}
