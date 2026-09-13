package com.ctds.subject.application;

import java.time.LocalDateTime;

/**
 * 审核清单条目（WBS-3.1.5 hifi 接口契约；固定"待审核"过滤，申请时间升序）。
 * subjectType 返回枚举名（与注册接口口径一致）。
 */
public record ReviewQueueItem(String subjectNo, String subjectName, String subjectType, LocalDateTime createdAt) {
}
