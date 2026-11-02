package com.ctds.metering.domain.billing;

import java.util.List;

/**
 * 账单明细仓储端口（hifi §2）：只插入与读取，无更新或删除路径（账单不可变，B10-3）。
 */
public interface BillDetailRepository {

    void insertAll(List<BillDetailLine> lines);

    List<BillDetailLine> findByBillNo(String billNo);

    /** 按次明细关联的计量流水 id 集合（对账缺失 / 多出条目比对）。 */
    List<Long> findMeteringFlowIds(String billNo);
}
