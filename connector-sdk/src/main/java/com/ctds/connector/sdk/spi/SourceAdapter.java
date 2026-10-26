package com.ctds.connector.sdk.spi;

/**
 * 适配器 SPI（规格行为 11 规则 1："适配器随连接器分发〔SDK 框架，3.6.2〕"）。
 *
 * <p>四类源各由对应适配器承载（政务源归 3.6.5，三类通用源归 3.6.6）——
 * 本 SDK 骨架只定义框架契约，<b>零实现类</b>。源接入须先登记后使用：
 * 未登记的源不得被适配器读取（默认拒绝，行为 11 规则 2）。</p>
 */
public interface SourceAdapter {

    /** 本适配器承载的源类型。 */
    SourceType type();

    /**
     * 连通性自检（行为 13）：只读探测（连通 / 凭据 / 权限三查），不产生源端数据变更；
     * 失败原因三类可枚举（网络不可达 / 凭据无效 / 权限不足）。自检通过不构成业务授权。
     */
    SourceCheckResult check(SourceRegistration registration);

    /**
     * 源数据读取（行为 14）：<b>先判定后取数</b>——使用控制判定放行后方可调用本方法；
     * 判定拒绝时不得触达源（交付记录 / 留痕 / 计量事件口径与 C-5.1 完全一致，拒绝零计量）。
     */
    SourceReadResult read(SourceRegistration registration, SourceReadRequest request);
}
