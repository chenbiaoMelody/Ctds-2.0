package com.ctds.space.interfaces.dto;

import com.ctds.space.application.CreateSpaceCommand;
import com.ctds.space.domain.AccessMode;
import com.ctds.space.domain.SceneType;
import com.ctds.space.domain.Visibility;
import java.time.LocalDateTime;

/**
 * 创建空间请求（WBS-3.2.3 hifi §1 端点 1）：要素四项必填（缺失 → 1006C0005 逐字段，应用服务校验），
 * 简介/生效期可选；要素取值非法（枚举外值/日期格式错）= 请求体格式不合法（1000C0001，通用通道）。
 */
public record CreateSpaceRequest(
        String name,
        SceneType sceneType,
        AccessMode accessMode,
        Visibility visibility,
        String intro,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo) {

    /** 原始载荷转命令（ownerSubjectNo/normalizedName 由应用服务经身份上下文与归一化补齐）。 */
    public CreateSpaceCommand toCommand() {
        return new CreateSpaceCommand(null, name, null, sceneType, accessMode, visibility, intro,
                effectiveFrom, effectiveTo);
    }
}
