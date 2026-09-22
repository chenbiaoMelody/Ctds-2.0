package com.ctds.did.domain;

/**
 * DID → KMS 密钥对托管端口（WBS-3.1.8 hifi §4.2）：生成 SM2 密钥对（私钥只存 KMS，仅返回公钥）。
 * 失败（KMS 不可达等）抛异常，由应用层收敛为 PENDING_ISSUE 业务态（行为 1 规则 5）。
 */
public interface DidKmsClient {

    /** 生成 SM2 密钥对并返回公钥 hex；KMS 不可达/失败抛异常。 */
    String createKeyPair(String keyRef);
}
