package com.ctds.delivery.domain;

import java.time.LocalDateTime;

/**
 * 沙箱动作留痕（hifi §2.3）：字段最小必要；不含内容、路径、容器、镜像、命令与密钥材料。
 */
public record SandboxActionLog(Long id, String sandboxNo, String jobNo, String contractNo,
        String operatorNo, String action, String result, String reasonCode,
        LocalDateTime createdAt) {
}
