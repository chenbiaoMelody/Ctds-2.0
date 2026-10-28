package com.ctds.connector.domain;

/**
 * 适配器探测非预期失败（hifi §7-③：适配器内部异常 / 报文解析失败）。
 *
 * <p>与三类业务失败原因严格区分：业务失败是 200 结论（{@code passed=false}），本异常属系统侧
 * 非预期，出站映射 502 + 中性文案（不暴露内部实现），并留痕 FAILURE。</p>
 */
public class ConnectorAdapterProbeException extends RuntimeException {

    /** 中性文案（服务端常量，不拼接任何内部信息）。 */
    public static final String PROBE_FAILED_MESSAGE = "源连通性探测失败，请稍后重试";

    public ConnectorAdapterProbeException(final Throwable cause) {
        super(PROBE_FAILED_MESSAGE, cause);
    }
}
