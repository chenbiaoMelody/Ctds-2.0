package com.ctds.contract.domain.policy;

import java.util.Objects;

/**
 * 使用请求值对象（WBS-3.4.5 hifi §2 判定入口契约）：谁发起的这次判定（requesterNo）+
 * 动作类型 + 本次使用声明的用途/地域（可空——与策略文本要素精确等值比较，Q5-A）。
 *
 * <p>requesterNo 只记"谁发起的这次判定"，不校验入驻状态（消费方在真实链路负责身份——
 * hifi §5）；R12 可见性判定不依赖本对象（执行记录默认参与方 + 治理可见）。</p>
 *
 * @param requesterNo 发起主体号（必填）
 * @param actionType  动作类型（USE / REDISTRIBUTE）
 * @param purpose     本次使用声明的用途（可空；与 purpose.text 精确等值比较，请求侧 trim 对称）
 * @param territory   本次使用声明的地域（可空；与 territory.text 精确等值比较，请求侧 trim 对称）
 */
public record UsageRequest(String requesterNo, UsageActionType actionType, String purpose,
        String territory) {

    public UsageRequest {
        Objects.requireNonNull(requesterNo, "requesterNo 必填");
        Objects.requireNonNull(actionType, "actionType 必填");
    }
}
