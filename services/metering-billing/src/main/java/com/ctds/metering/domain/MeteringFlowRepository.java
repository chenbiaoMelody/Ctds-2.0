package com.ctds.metering.domain;

import java.util.List;

/** 计量流水仓储（幂等 upsert + 本方读面聚合查询；hifi §2/§3）。 */
public interface MeteringFlowRepository {

    /** 幂等落库：uk_metering_source 兜底，重复源单号零重计；返回 true = 本次新增行。 */
    boolean upsert(MeteringFlow flow);

    /** 本方合约流水（occurred_at 倒序全列；演示量级不分页——hifi §1 R-M1 登记演进）。 */
    List<MeteringFlow> findByContract(String contractNo);

    /** 合约 × 自然月聚合（period 降序；B8-1）。 */
    List<PeriodFlowSummary> summarizeByPeriod(String contractNo);

    /** 本合约流水总条数（R-M3 勾稽读数腿④）。 */
    long countByContract(String contractNo);
}
