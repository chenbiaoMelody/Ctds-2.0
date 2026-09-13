package com.ctds.subject.application;

/**
 * 证照上传结果（hifi 接口契约）：OCR 要素回填仅供核对，不直接生效（规格行为 2 第 2 条）。
 *
 * @param fileName   上传文件名
 * @param ocrResult  识别要素（回填表单）
 */
public record LicenseUploadResult(String fileName, OcrElements ocrResult) {
}
