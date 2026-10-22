package com.ctds.delivery.interfaces.dto;

/** 沙箱端点请求体（hifi §7.1）：只给业务要素，不传命令 / 路径 / 配额 / 镜像。 */
public final class SandboxRequest {

    private SandboxRequest() {
    }

    /** 申请开通：凭据 = 已生效合约编号。 */
    public record CreateRequest(String contractNo) {
    }

    /** 受控作业：只给清单内 jobId（用途 / 地域为可选策略要素）。 */
    public record RunJobRequest(String jobId, String purpose, String territory) {
    }
}
