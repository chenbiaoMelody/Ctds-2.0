package com.ctds.contract.domain;

/**
 * 合约模板类型（三类受控枚举，PRD C-4.1 原文；行为 1 规则 2"预置三类模板"）。
 */
public enum TemplateType {

    /** 公共数据授权。 */
    PUBLIC_DATA_AUTHORIZATION("公共数据授权"),
    /** API 调用。 */
    API_CALL("API调用"),
    /** 隐私计算。 */
    PRIVACY_COMPUTING("隐私计算");

    private final String displayName;

    TemplateType(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
