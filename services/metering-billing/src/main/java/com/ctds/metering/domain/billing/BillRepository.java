package com.ctds.metering.domain.billing;

import com.ctds.common.pagination.PageQuery;
import java.util.List;
import java.util.Optional;

/**
 * 账单仓储端口（hifi §2）：只提供插入、读面检索与三种状态流转（确认 / 冻结 / 解冻）；
 * 无金额或明细的更新路径（账单不可变，B10-3）。
 */
public interface BillRepository {

    /** 插入账单（唯一键冲突由仓储原样抛出 DuplicateKeyException，由出账服务映射 409）。 */
    void insert(Bill bill);

    Optional<Bill> findByBillNo(String billNo);

    /** 同合约同周期普通账单（DISTINCT 唯一）。 */
    Optional<Bill> findNormal(String contractNo, String period);

    /** 指定原单的全部更正单（读面双向可溯）。 */
    List<Bill> findCorrectionsOf(String originalBillNo);

    /**
     * 该合约该周期的全部账单（含更正单；对账差异冻结 / 通过解冻对象）。
     * <p>须在对账事务内调用：读取即加行锁（FOR UPDATE），同合约同周期的冻结 / 解冻串行化，
     * 避免"最新对账记录为差异但账单未冻结"的 fail-open 窗口（hifi §13 E3）。</p>
     */
    List<Bill> findByContractAndPeriod(String contractNo, String period);

    /** 本方读面（subject 为合约需方或供方）。 */
    List<Bill> findMine(String subject, BillSearchCriteria criteria, PageQuery page);

    long countMine(String subject, BillSearchCriteria criteria);

    /** 治理全量读面（B12-2）。 */
    List<Bill> findGovernance(BillSearchCriteria criteria, PageQuery page);

    long countGovernance(BillSearchCriteria criteria);

    /** 确认：仅 ISSUED → CONFIRMED（返回受影响行数）。 */
    int markConfirmed(String billNo);

    /** 冻结：非 FROZEN → FROZEN，原状态由行内真值写入（不传应用侧旧值）。 */
    int markFrozen(String billNo);

    /** 解冻：FROZEN → 原状态（清空 status_before_freeze）。 */
    int markRestored(String billNo, BillStatus restoredStatus);
}
