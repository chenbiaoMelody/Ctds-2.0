package com.ctds.space.domain;

/**
 * 空间名称归一化（3.2.2 移交①兑现，WBS-3.2.3 hifi §4；判重前提 = 列排序规则 utf8mb4_0900_ai_ci——
 * 大小写/重音不敏感、NO PAD，尾随空格不做 MySQL PAD 等价，故归一化必须先去首尾空白，移交②）。
 *
 * <p>规则（hifi §4 定稿）：① 去除 Unicode 控制字符（Cc 控制字符与 Cf 格式字符，
 * 含零宽 U+200B~U+200D、BOM U+FEFF）；② 空白集显式定义 = {@link Character#isWhitespace} ∪ U+3000（全角空格）
 * ∪ U+00A0（nbsp），首尾 trim、内部连续空白折叠为单个空格；③ 长度校验在归一化后执行（≤128，应用服务落）。</p>
 */
public final class SpaceNameNormalizer {

    /** 全角空格（U+3000）：显式并入空白集（hifi §4 ②）。 */
    private static final int IDEOGRAPHIC_SPACE = 0x3000;
    /** 不换行空格（U+00A0）：Character.isWhitespace 不计（非断行），显式并入（hifi §4 ②）。 */
    private static final int NO_BREAK_SPACE = 0x00A0;
    /** 折叠后的内部空白分隔符（半角空格）。 */
    private static final char FOLDED_SPACE = ' ';

    private SpaceNameNormalizer() {
    }

    /**
     * 归一化：控制/格式字符去除 → 显式空白集折叠 → 首尾 trim。null 视为空串；
     * 结果可能为空串（纯空白输入），由调用方按"名称不能为空"处置。
     */
    public static String normalize(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        final StringBuilder normalized = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); ) {
            final int codePoint = raw.codePointAt(i);
            i += Character.charCount(codePoint);
            final int type = Character.getType(codePoint);
            if (type == Character.CONTROL || type == Character.FORMAT) {
                continue;
            }
            if (isWhitespace(codePoint)) {
                // 内容出现过的空白才折叠为分隔符（同时消灭首尾空白）
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                normalized.append(FOLDED_SPACE);
                pendingSpace = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        return normalized.toString();
    }

    private static boolean isWhitespace(final int codePoint) {
        return Character.isWhitespace(codePoint) || codePoint == IDEOGRAPHIC_SPACE
                || codePoint == NO_BREAK_SPACE;
    }
}
