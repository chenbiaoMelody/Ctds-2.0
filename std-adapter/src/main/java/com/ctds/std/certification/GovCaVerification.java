package com.ctds.std.certification;

/**
 * 政务 CA 证书验证结论（WBS-3.1.4，规格行为 6 第 2 条 / 行为 7 第 3 条三要素）。
 *
 * @param passed           渠道结论：true 通过 / false 不通过
 * @param channelRequestNo 渠道请求流水号（留痕要素，业务侧原样落库）
 * @param failReason       不通过时的业务原因（通过时为 null）
 * @param unitName         证书校验出的单位名称（组织信息，非 L4；渠道未返回时为 null）
 * @param unitCode         证书校验出的单位统一社会信用代码（组织信息，非 L4；渠道未返回时为 null）
 * @param message          渠道附加说明（业务可读，可为 null）
 */
public record GovCaVerification(boolean passed, String channelRequestNo, String failReason,
        String unitName, String unitCode, String message) {

    public GovCaVerification {
        if (channelRequestNo == null || channelRequestNo.isBlank()) {
            throw new IllegalArgumentException("channelRequestNo must not be blank");
        }
        if (!passed && (failReason == null || failReason.isBlank())) {
            throw new IllegalArgumentException("failReason is required when not passed");
        }
    }
}
