package com.ctds.gateway.policy;

/**
 * 判定结果视图（hifi §4.1 响应消费形态）：
 *
 * <ul>
 *   <li>放行：{@code allowed=true}，携带合约参与方摘要（{@code applicantNo} = 需求方，
 *       供交付域校验"发起人 = 合约需求方"）；</li>
 *   <li>拒绝：{@code allowed=false}，{@code code}/{@code message}/{@code httpStatus} 均取自合约域
 *       原响应（越界一律原码透传，禁改写）；</li>
 *   <li>判定服务不可达 / 非合约域业务码：不构成本记录，抛 {@link PolicyUnavailableException}
 *       由上层 fail-closed 处置（503 + 1009S0001，不放行）。</li>
 * </ul>
 */
public record PolicyDecision(boolean allowed, String code, String message, int httpStatus,
        String applicantNo, String providerNo) {

    static PolicyDecision allowed(final String applicantNo, final String providerNo) {
        return new PolicyDecision(true, "0", "success", 200, applicantNo, providerNo);
    }

    static PolicyDecision denied(final String code, final String message, final int httpStatus) {
        return new PolicyDecision(false, code, message, httpStatus, null, null);
    }
}
