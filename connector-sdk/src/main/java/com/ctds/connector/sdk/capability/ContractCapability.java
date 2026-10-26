package com.ctds.connector.sdk.capability;

import com.ctds.connector.sdk.model.ContractView;
import com.ctds.connector.sdk.model.CreateContractCommand;
import com.ctds.connector.sdk.model.PageResult;

/**
 * 合约管理门面（规格行为 2 合约腿）：协商 / 签署 / 履约由合约域提供——SDK 只封装。
 */
public interface ContractCapability {

    /** 创建合约磋商（发起即协商中——平台口径）。 */
    ContractView createNegotiation(CreateContractCommand command);

    /** 查我的合约（平台分页信封；行字段集见 {@link ContractView} 并集口径）。 */
    PageResult<ContractView> listMyContracts(int pageNum, int pageSize);

    /** 查合约详情（含状态——交付凭据 = 已生效合约的查验入口）。 */
    ContractView getContract(String contractNo);
}
