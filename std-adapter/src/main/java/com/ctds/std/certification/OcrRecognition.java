package com.ctds.std.certification;

/**
 * 营业执照 OCR 识别结论（WBS-3.1.3，规格行为 2）。
 *
 * @param recognizable     渠道是否识别成功（false = 无法识别请重传，其余要素字段无意义）
 * @param subjectName      识别的主体名称
 * @param uscc             识别的统一社会信用代码
 * @param legalPerson      识别的法定代表人姓名
 * @param regAddress       识别的注册地址
 * @param channelRequestNo 渠道请求流水号（留痕要素，业务侧原样落库）
 * @param message          业务可读结论说明（服务端常量口径，不拼接用户输入）
 */
public record OcrRecognition(
        boolean recognizable,
        String subjectName,
        String uscc,
        String legalPerson,
        String regAddress,
        String channelRequestNo,
        String message) {

    public OcrRecognition {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (channelRequestNo == null || channelRequestNo.isBlank()) {
            throw new IllegalArgumentException("channelRequestNo must not be blank");
        }
        if (recognizable && (isBlank(subjectName) || isBlank(uscc) || isBlank(legalPerson) || isBlank(regAddress))) {
            throw new IllegalArgumentException("recognizable result requires all four elements");
        }
    }

    /** 不可识别结论（业务提示重传，不产生部分识别结果——规格行为 2 第 3 条）。 */
    public static OcrRecognition unrecognizable(final String channelRequestNo, final String message) {
        return new OcrRecognition(false, null, null, null, null, channelRequestNo, message);
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
