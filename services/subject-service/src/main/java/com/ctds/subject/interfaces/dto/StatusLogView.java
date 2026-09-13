package com.ctds.subject.interfaces.dto;

import com.ctds.subject.domain.StatusTransition;

/** 流转留痕视图（前状态/后状态/触发方/时间四要素 + 操作人与备注；注册建档前状态记 NONE）。 */
public record StatusLogView(String fromStatus, String toStatus, String triggerRole, String operator,
        String remark, String createdAt) {

    public static StatusLogView from(final StatusTransition transition) {
        return new StatusLogView(
                transition.fromStatus() == null ? "NONE" : transition.fromStatus().name(),
                transition.toStatus().name(),
                transition.triggerRole().name(),
                transition.operator(),
                transition.remark(),
                transition.createdAt().toString());
    }
}
