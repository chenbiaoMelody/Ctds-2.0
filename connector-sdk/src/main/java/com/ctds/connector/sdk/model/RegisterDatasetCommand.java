package com.ctds.connector.sdk.model;

/** 数据集登记命令（对齐平台目录域登记要素——名称 / 类型 / 简介 / 申报分类分级）。 */
public record RegisterDatasetCommand(String name, String type, String intro,
        String declareCategory, String declareLevel) {
}
