package com.ctds.subject.application;

/**
 * 证照核对确认命令（hifi 接口契约；确认值与 OCR 识别值比对，信用代码不一致阻断——规格行为 2 第 4 条）。
 */
public record ConfirmationCommand(String subjectName, String uscc, String legalPerson, String regAddress) {
}
