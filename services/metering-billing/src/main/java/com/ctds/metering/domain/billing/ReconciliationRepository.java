package com.ctds.metering.domain.billing;

import com.ctds.common.pagination.PageQuery;
import java.util.List;

/**
 * 对账记录仓储端口（hifi §2）：仅插入与读取（对账记录不可改）。
 */
public interface ReconciliationRepository {

    void insert(Reconciliation reconciliation);

    List<Reconciliation> find(String contractNo, String period, PageQuery page);

    long count(String contractNo, String period);
}
