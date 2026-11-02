package com.ctds.metering.domain.billing;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 合约计费只读端口（跨 schema 直读 ctds_contract.contract，hifi §3.1；零写零改上游）。
 */
public interface BillingContractReader {

    Optional<ContractPricingSnapshot> findByContractNo(String contractNo);

    /** 周期内可出账的付费档合约（自动出账扫描口径，hifi §4）。 */
    List<ContractPricingSnapshot> findBillable(LocalDateTime periodStart, LocalDateTime periodEnd);
}
