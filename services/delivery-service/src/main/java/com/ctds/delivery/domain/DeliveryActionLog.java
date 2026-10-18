package com.ctds.delivery.domain;

import java.time.LocalDateTime;

/** 交付域动作留痕（DB 行；不含文件本体/原文/路径/密钥材料）。 */
public record DeliveryActionLog(Long id, String deliveryNo, String contractNo, String operatorNo,
        String action, String result, String reasonCode, LocalDateTime createdAt) {
}
