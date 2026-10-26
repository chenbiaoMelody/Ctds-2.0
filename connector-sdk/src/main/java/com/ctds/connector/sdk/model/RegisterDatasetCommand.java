package com.ctds.connector.sdk.model;

import java.util.List;

/**
 * 数据集登记命令（对齐平台 RegisterDatasetRequest：type 取平台枚举名 DATASET / API / REPORT / MODEL；
 * declareLevel 取平台 DeclareLevel 枚举名；tags / declareImportant 可选）。
 */
public record RegisterDatasetCommand(String name, String type, String intro, List<String> tags,
        String declareCategory, String declareLevel, Boolean declareImportant) {
}
