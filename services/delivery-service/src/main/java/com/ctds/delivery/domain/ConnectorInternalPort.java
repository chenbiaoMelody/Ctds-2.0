package com.ctds.delivery.domain;

/**
 * 连接器内部面端口（WBS-3.6.8 W3 / W4，hifi §1-D / §1-E）：交付链消费连接器既有内部面
 * （占用登记 / 释放 / 读连接器属主 / 内部源读取），服务身份 {@code delivery-service} +
 * {@code connector-internal}（演示期信任边界 DB-03 沿承，任何档位不混入业务身份）。
 *
 * <p>实现（{@code infrastructure.ConnectorInternalClient}）将不可达 / 超时 / 非 2xx /
 * 业务拒绝统一收敛为 {@link ConnectorInternalException}（业务可读原因，不含内部码与源信息），
 * 不做重试（ADR-026 A4 维持不做）；占用 / 释放均为幂等语义。</p>
 */
public interface ConnectorInternalPort {

    /** 占用登记（幂等；jobRef = 交付编号）。连接器非 REGISTERED / 并发冲突 → 异常。 */
    void occupy(String connectorNo, String jobRef);

    /** 释放占用（幂等；重复释放安静成功）。 */
    void release(String connectorNo, String jobRef);

    /** 读连接器属主主体编号（不存在 / 无权 → 异常）。 */
    String getConnectorOwnerSubjectNo(String connectorNo);

    /** 内部源读取（成功 / 业务失败均返回结论；网络 / 凭证类失败 → 异常）。 */
    ConnectorSourceRead readSource(String connectorNo, String sourceNo, String jobRef,
            String contractNo, String purpose);

    /** 内部源读取结论（success=false = 适配器业务失败，如源拒绝 / 凭据无效——映射取数失败）。 */
    record ConnectorSourceRead(boolean success, String summary) {
    }
}
