package com.ctds.connector.sdk.capability;

import com.ctds.connector.sdk.ConnectorException;
import com.ctds.connector.sdk.model.UsageRequest;
import com.ctds.connector.sdk.model.UsageResult;
import com.ctds.connector.sdk.model.UsageSummaryView;

/**
 * 使用控制门面（规格行为 5 / §7 Q6-A）：判定唯一权威在平台合约域策略引擎——
 * SDK <b>消费判定结果</b>，不复制策略、不建本地第二判定点。
 *
 * <p>三态语义（fail-closed，无本地放行路径）：
 * ① 平台放行 → 返回成功结果（含被交付资源）；② 平台拒绝（越界 / 状态门槛等）→ 抛
 * {@link ConnectorException}〔平台原码原文〕且<b>不重试</b>；③ 平台不可达 / 超时 →
 * 抛 {@link ConnectorException}〔SDK 中性分类〕，<b>绝不返回成功</b>。</p>
 */
public interface UsageControlCapability {

    /**
     * 执行使用：经<b>平台网关交付面</b>请求被交付资源（判定在网关转发前由平台完成——
     * SDK 不直调平台内部判定端点）；放行返回资源，越界拒绝原码透传。
     */
    UsageResult executeUsage(UsageRequest request);

    /** 查用量汇总（只读）。 */
    UsageSummaryView getUsageSummary(String contractNo);
}
