package com.ctds.evidence.domain;

import java.util.List;

/**
 * 仓储分页切片（hifi §2：page 与 countByStatus 同端口；total 随切片返回，避免二次计数漂移）。
 */
public record EvidencePage(List<EvidenceRecord> list, long total) {

    public EvidencePage {
        list = (list == null) ? List.of() : List.copyOf(list);
    }
}
