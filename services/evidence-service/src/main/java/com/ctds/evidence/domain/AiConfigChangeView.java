package com.ctds.evidence.domain;

/**
 * WBS-3.10.4 变更留痕读面行（五要素；沿 RbacChangeView 先例：领域记录直出 API 视图）。
 * beforeValue/afterValue 为 null 表示该侧原值为空（前端显示"（空）"）。
 */
public record AiConfigChangeView(String changedAt, String changedBy, String fieldPath,
        String beforeValue, String afterValue) {
}
