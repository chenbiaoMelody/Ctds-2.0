package com.ctds.subject.application;

/**
 * OCR 识别回填要素（业务可读四要素，规格行为 2 第 2 条；回填仅供核对确认，不直接生效）。
 *
 * @param subjectName 识别的主体名称
 * @param uscc        识别的统一社会信用代码
 * @param legalPerson 识别的法定代表人姓名
 * @param regAddress  识别的注册地址
 */
public record OcrElements(String subjectName, String uscc, String legalPerson, String regAddress) {
}
