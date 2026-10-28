package com.ctds.connector.sdk.adapter.gov;

import com.ctds.connector.sdk.spi.SourceRegistration;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 政务共享交换平台标准报文（请求 / 应答共用，hifi §7-⑤）。
 *
 * <p>请求侧：{@code sourceNo} / {@code endpoint} / {@code contractNo} / {@code purpose}
 * （{@code contractNo} 为空 = 连通性自检；有值 = 取数请求）。<br>
 * 应答侧：{@code rows} / {@code code} / {@code message}，{@code code} 取 {@code OK} 或三类失败原因。</p>
 *
 * <p>诚实声明：V1.0 报文协议为本地约定（真实政务平台对接属部署实施期），模拟源按本约定应答。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record GovExchangeMessage(
        @JsonProperty("sourceNo") String sourceNo,
        @JsonProperty("endpoint") String endpoint,
        @JsonProperty("contractNo") String contractNo,
        @JsonProperty("purpose") String purpose,
        @JsonProperty("rows") int rows,
        @JsonProperty("code") String code,
        @JsonProperty("message") String message) {

    /** 通过。 */
    static final String OK = "OK";
    /** 凭据无效。 */
    static final String INVALID_CREDENTIAL = "INVALID_CREDENTIAL";
    /** 网络不可达。 */
    static final String NETWORK_UNREACHABLE = "NETWORK_UNREACHABLE";
    /** 权限不足。 */
    static final String INSUFFICIENT_PERMISSION = "INSUFFICIENT_PERMISSION";

    /**
     * 构造请求报文。SPI 登记模型不含源编号（凭据引用制），V1.0 以连接器编号标识调用方。
     */
    static GovExchangeMessage request(final SourceRegistration registration, final String contractNo,
            final String purpose) {
        return new GovExchangeMessage(registration.ownerConnectorNo(), registration.endpoint(),
                contractNo, purpose, 0, null, null);
    }

    /** 构造失败应答报文。 */
    static GovExchangeMessage failure(final String code, final String message) {
        return new GovExchangeMessage(null, null, null, null, 0, code, message);
    }
}
