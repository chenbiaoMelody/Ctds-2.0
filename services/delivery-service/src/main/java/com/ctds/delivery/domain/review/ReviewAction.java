package com.ctds.delivery.domain.review;

/** 评价域动作码（hifi §2：提交 / 更新 / 隐藏三类，全程留痕）。 */
public enum ReviewAction {
    CREATE,
    UPDATE,
    HIDE
}
