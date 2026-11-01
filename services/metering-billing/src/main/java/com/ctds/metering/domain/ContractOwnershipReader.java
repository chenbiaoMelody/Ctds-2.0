package com.ctds.metering.domain;

import java.util.Optional;

/** 合约归属读取端口（B8-4 归属校验权威来源；实时读，流水表不冗余双方）。 */
public interface ContractOwnershipReader {

    /** 合约不存在 = Optional.empty()；调用方以同码同文 404 防枚举（1011C0008）。 */
    Optional<ContractOwnership> findByContractNo(String contractNo);
}
