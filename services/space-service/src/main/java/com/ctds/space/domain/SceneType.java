package com.ctds.space.domain;

/**
 * 场景类型（规格行为 1 要素；lofi Q8-A 裁决枚举三值，规格行文示例"普惠金融/医疗验证/其他"）。
 * 扩充取值须走规格变更流程。
 */
public enum SceneType {

    /** 普惠金融场景。 */
    FINTECH("普惠金融"),
    /** 医疗验证场景。 */
    MEDICAL("医疗验证"),
    /** 其他场景。 */
    OTHER("其他");

    private final String displayName;

    SceneType(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
