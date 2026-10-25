package com.ctds.delivery.domain;

import java.time.LocalDateTime;

/**
 * 交付记录（DB 行映射；只含业务要素与校验值，不含文件本体/原文/路径/密钥材料）。
 * {@code keyRef} 为密钥编号（非密钥材料）；FILE 记录含包字段，API 记录为 null；
 * SANDBOX_EXPORT 记录（WBS-3.5.5）另含沙箱任务号与结果物标识，其余形态两列恒 null。
 */
public record Delivery(
        Long id,
        String deliveryNo,
        String contractNo,
        long productId,
        String productName,
        DeliveryType deliveryType,
        String requesterNo,
        String providerNo,
        String fileId,
        String fileName,
        Long fileSize,
        String packageId,
        String checksum,
        String keyRef,
        String result,
        String idempotencyKey,
        String sandboxNo,
        String resultName,
        LocalDateTime createdAt) {
}
