package com.ctds.subject.interfaces.dto;

import com.ctds.subject.application.CancellationResult;

/** 撤销结果视图。 */
public record CancellationView(String subjectNo, String status, boolean cancelled) {

    public static CancellationView from(final CancellationResult result) {
        return new CancellationView(result.subjectNo(), result.status().name(), result.cancelled());
    }
}
