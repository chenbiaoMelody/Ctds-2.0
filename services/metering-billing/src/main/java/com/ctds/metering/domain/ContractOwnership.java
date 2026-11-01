package com.ctds.metering.domain;

/** 合约归属快照（ctds_contract.contract 双方字段实时读——单一权威，hifi §2 查询 3）。 */
public record ContractOwnership(String contractNo, String requesterNo, String providerNo) {
}
