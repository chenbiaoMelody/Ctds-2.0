package com.ctds.space.application;

import com.ctds.space.domain.AccessMode;
import com.ctds.space.domain.SceneType;
import com.ctds.space.domain.Visibility;
import java.time.LocalDateTime;

/**
 * 创建空间内部命令（WBS-3.2.3 hifi §5 幂等键载体：ownerSubjectNo + normalizedName 归一化后参与幂等键，
 * 同一归一化名的不同空白形态重复提交 = 同键复用首次结果）。name 保留原始输入供展示落库。
 */
public record CreateSpaceCommand(
        String ownerSubjectNo,
        String name,
        String normalizedName,
        SceneType sceneType,
        AccessMode accessMode,
        Visibility visibility,
        String intro,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo) {
}
