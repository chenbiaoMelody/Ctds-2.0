package com.ctds.delivery.interfaces.dto;

import com.ctds.delivery.domain.Delivery;
import java.time.LocalDateTime;

/** 交付记录读面（不含路径、keyRef、文件本体与原文）。 */
public final class DeliveryViews {

    private DeliveryViews() {
    }

    public record DeliveryView(String deliveryNo, String contractNo, long productId, String productName,
            String deliveryType, String requesterNo, String providerNo, String fileId, String fileName,
            Long fileSize, String packageId, String checksum, String result, LocalDateTime createdAt) {

        public static DeliveryView from(final Delivery delivery) {
            return new DeliveryView(delivery.deliveryNo(), delivery.contractNo(), delivery.productId(),
                    delivery.productName(), delivery.deliveryType().name(), delivery.requesterNo(),
                    delivery.providerNo(), delivery.fileId(), delivery.fileName(), delivery.fileSize(),
                    delivery.packageId(), delivery.checksum(), delivery.result(), delivery.createdAt());
        }
    }

    /** 沙箱导出记录视图（WBS-3.5.5 hifi §5/§7.1：谁 / 何时 / 合约与任务 / 结果物标识 / 结果）。 */
    public record ExportView(String deliveryNo, String deliveryType, String contractNo,
            String sandboxNo, String resultName, String requesterNo, String providerNo,
            String fileName, Long fileSize, String checksum, String result,
            LocalDateTime createdAt) {

        public static ExportView from(final Delivery delivery) {
            return new ExportView(delivery.deliveryNo(), delivery.deliveryType().name(),
                    delivery.contractNo(), delivery.sandboxNo(), delivery.resultName(),
                    delivery.requesterNo(), delivery.providerNo(), delivery.fileName(),
                    delivery.fileSize(), delivery.checksum(), delivery.result(),
                    delivery.createdAt());
        }
    }
}
