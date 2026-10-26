package com.ctds.connector.sdk;

/**
 * 连接器 SDK 唯一异常类型（lofi §1-7 / Q6-A）。
 *
 * <p>平台拒绝时<b>原码原文透传</b>（platformCode / platformMessage 携带平台 9 位错误码与文案，
 * 不二次包装、不改写——防枚举同码同文口径原样到达参与方）；SDK 自身错误按
 * {@link SdkErrorType} 中性三分类（无码值）。</p>
 */
public class ConnectorException extends RuntimeException {

    private final String platformCode;

    private final String platformMessage;

    private final SdkErrorType sdkErrorType;

    /** 平台拒绝透传构造（platformCode 必填）。 */
    public ConnectorException(String platformCode, String platformMessage) {
        super("平台拒绝：" + platformCode + " " + platformMessage);
        this.platformCode = platformCode;
        this.platformMessage = platformMessage;
        this.sdkErrorType = null;
    }

    /** SDK 自身错误构造（中性分类，无码值）。 */
    public ConnectorException(SdkErrorType sdkErrorType, String message, Throwable cause) {
        super(message, cause);
        this.platformCode = null;
        this.platformMessage = null;
        this.sdkErrorType = sdkErrorType;
    }

    public String platformCode() {
        return platformCode;
    }

    public String platformMessage() {
        return platformMessage;
    }

    public SdkErrorType sdkErrorType() {
        return sdkErrorType;
    }
}
