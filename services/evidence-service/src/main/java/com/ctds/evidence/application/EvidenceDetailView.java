package com.ctds.evidence.application;

import java.util.List;

/**
 * 存证详情（记录 + 状态流转与核验留痕，按时间正序）。
 */
public record EvidenceDetailView(EvidenceRecordView record, List<EvidenceActionView> actions) {

    public EvidenceDetailView {
        actions = (actions == null) ? List.of() : List.copyOf(actions);
    }
}
