package com.ctds.connector.sdk;

import com.ctds.connector.sdk.capability.ContractCapability;
import com.ctds.connector.sdk.model.ContractView;
import com.ctds.connector.sdk.model.CreateContractCommand;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;

/** 合约管理门面默认实现（协商 / 签署 / 履约由合约域提供）。 */
final class DefaultContractCapability implements ContractCapability {

    private final PlatformClient client;

    DefaultContractCapability(PlatformClient client) {
        this.client = client;
    }

    @Override
    public ContractView createNegotiation(CreateContractCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command 不可为空");
        }
        return client.post("/api/v1/contracts", command, Map.of(),
                new ParameterizedTypeReference<PlatformResponse<ContractView>>() {
                });
    }

    @Override
    public List<ContractView> listMyContracts() {
        return client.get("/api/v1/contracts/mine",
                new ParameterizedTypeReference<PlatformResponse<List<ContractView>>>() {
                });
    }

    @Override
    public ContractView getContract(String contractNo) {
        DefaultIdentityCapability.requireText(contractNo, "contractNo");
        return client.get("/api/v1/contracts/" + contractNo,
                new ParameterizedTypeReference<PlatformResponse<ContractView>>() {
                });
    }
}
