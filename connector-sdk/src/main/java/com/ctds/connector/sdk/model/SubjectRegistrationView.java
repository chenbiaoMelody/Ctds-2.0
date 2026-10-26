package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 主体登记详情视图（对齐平台 SubjectView 核心字段：subjectNo / subjectName / status）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubjectRegistrationView(String subjectNo, String subjectName, String status) {
}
