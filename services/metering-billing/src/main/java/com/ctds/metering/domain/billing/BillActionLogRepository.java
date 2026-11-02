package com.ctds.metering.domain.billing;

import java.util.List;

/**
 * 账单动作留痕仓储端口（hifi §2）：仅插入与按账单读取（留痕不可改）。
 */
public interface BillActionLogRepository {

    void insert(BillActionLog actionLog);

    List<BillActionLog> findByBillNo(String billNo);
}
