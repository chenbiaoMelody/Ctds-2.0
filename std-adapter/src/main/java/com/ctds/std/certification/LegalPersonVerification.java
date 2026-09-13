package com.ctds.std.certification;

/**
 * 法人实人核验结论（WBS-3.1.3，规格行为 3 / 行为 7 第 3 条三要素）。
 *
 * @param passed          渠道结论：true 通过 / false 不通过
 * @param channelRequestNo 渠道请求流水号（留痕要素，业务侧原样落库）
 * @param failReason      不通过时的业务原因（通过时为 null）
 */
public record LegalPersonVerification(boolean passed, String channelRequestNo, String failReason) {

    public LegalPersonVerification {
        if (channelRequestNo == null || channelRequestNo.isBlank()) {
            throw new IllegalArgumentException("channelRequestNo must not be blank");
        }
        if (!passed && (failReason == null || failReason.isBlank())) {
            throw new IllegalArgumentException("failReason is required when not passed");
        }
    }
}
