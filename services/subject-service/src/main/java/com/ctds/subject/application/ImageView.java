package com.ctds.subject.application;

/**
 * 证照影像查看结果（hifi 接口契约；解密后 base64 数据 URL，查看行为已留审计）。
 */
public record ImageView(String fileName, String dataUrl) {
}
