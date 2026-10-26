package com.ctds.connector.sdk;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.web.util.UriUtils;

/**
 * 交付链凭据要素工具（ADR-022 §2.5 / 网关 {@code DeliveryCredentialGlobalFilter} 契约）：
 * 合约编号<b>原值</b>发送且须匹配网关字符集（{@code [A-Za-z0-9._-]{1,64}}，网关不解码该头）；
 * 用途 / 域为策略要素，可空（空值不带头，交平台判定）。
 *
 * <p>用途 / 域统一 <b>RFC 3986 UTF-8 百分号编码</b>发送——网关以 {@code UriUtils.decode}
 * 解码配对（空格编码为 {@code %20}，不可用 {@code application/x-www-form-urlencoded} 语义把空格
 * 编成 {@code +}）；编码后亦天然阻断 CR/LF 头注入。</p>
 */
final class Credentials {

    /** 网关 / 交付域合约编号字符集（网关 400 前先本地 fail-fast）。 */
    private static final Pattern CONTRACT_NO = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** 要素解码后长度上限（网关口径 ≤256 字符）。 */
    private static final int ELEMENT_MAX_LENGTH = 256;

    private Credentials() {
    }

    /** 凭据头三件（合约编号必填 + 用途/域可空）。 */
    static Map<String, String> headers(String contractNo, String purpose, String territory) {
        Map<String, String> headers = new HashMap<>();
        headers.put(PlatformClient.HEADER_CONTRACT_NO, requireContractNo(contractNo));
        putElement(headers, PlatformClient.HEADER_PURPOSE, purpose);
        putElement(headers, PlatformClient.HEADER_TERRITORY, territory);
        return headers;
    }

    /** 合约编号必填 + 字符集校验（CONFIG fail-fast，不发起平台调用）。 */
    static String requireContractNo(String contractNo) {
        if (contractNo == null || !CONTRACT_NO.matcher(contractNo).matches()) {
            throw new ConnectorException(SdkErrorType.CONFIG,
                    "contractNo 必填且仅含 [A-Za-z0-9._-]（长度 ≤64，网关凭据头口径）", null);
        }
        return contractNo;
    }

    /** 策略要素头（空白 = 未声明不带头；超长 / 非法编码值在本地拒绝）。 */
    static void putElement(Map<String, String> headers, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() > ELEMENT_MAX_LENGTH) {
            throw new ConnectorException(SdkErrorType.CONFIG,
                    name + " 长度超出上限（≤" + ELEMENT_MAX_LENGTH + " 字符）", null);
        }
        headers.put(name, UriUtils.encode(trimmed, StandardCharsets.UTF_8));
    }

    /** 幂等键校验（平台口径 {@code [A-Za-z0-9._-]{1,128}}，原值发送不编码）。 */
    static String requireIdempotencyKey(String value) {
        if (value == null || !Pattern.matches("[A-Za-z0-9._-]{1,128}", value)) {
            throw new ConnectorException(SdkErrorType.CONFIG,
                    "idempotencyKey 必填且仅含 [A-Za-z0-9._-]（长度 ≤128，平台幂等口径）", null);
        }
        return value;
    }

    /** 文件标识校验（平台口径 {@code [A-Za-z0-9._-]{1,64}}）。 */
    static String requireFileId(String value) {
        if (value == null || !Pattern.matches("[A-Za-z0-9._-]{1,64}", value)) {
            throw new ConnectorException(SdkErrorType.CONFIG,
                    "fileId 必填且仅含 [A-Za-z0-9._-]（长度 ≤64，平台交付口径）", null);
        }
        return value;
    }
}
