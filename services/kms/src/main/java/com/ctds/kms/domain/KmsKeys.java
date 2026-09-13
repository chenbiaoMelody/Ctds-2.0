package com.ctds.kms.domain;

/** KMS 内部密钥编号约定（编号是业务别名、非密钥材料，可入日志）。 */
public final class KmsKeys {

    /** 密钥库加密根密钥的信封取用别名（根密钥材料来自部署环境注入，hifi §3.4）。 */
    public static final String ROOT_KEY_REF = "kms-root";

    private KmsKeys() {
    }
}
