package com.ctds.delivery.domain;

import java.time.LocalDateTime;

/**
 * 沙箱动作留痕（hifi §2.3）：字段最小必要；不含内容、路径、容器、镜像、命令与密钥材料。
 * {@code resultName} 为结果物维度动作（提交 / 审核 / 导出与拒绝）填写，其余动作为 null。
 */
public record SandboxActionLog(Long id, String sandboxNo, String jobNo, String contractNo,
        String operatorNo, String action, String result, String reasonCode, String resultName,
        LocalDateTime createdAt) {
}
