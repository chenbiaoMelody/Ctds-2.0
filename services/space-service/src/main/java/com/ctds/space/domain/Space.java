package com.ctds.space.domain;

import java.time.LocalDateTime;

/**
 * 空间（一行 = 一个逻辑空间；WBS-3.2.2 hifi §1.1，逐列对应 space 表）。
 *
 * <p>活跃空间名称同一所有者唯一（uk_owner_norm_name）、解散名称全平台锁定（space_name_lock）；
 * 归一化名称为唯一性判定口径，归一化算法复用主体服务实现。</p>
 */
public record Space(
        Long id,
        String name,
        String normalizedName,
        SceneType sceneType,
        AccessMode accessMode,
        Visibility visibility,
        String intro,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,
        String ownerSubjectNo,
        SpaceStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
