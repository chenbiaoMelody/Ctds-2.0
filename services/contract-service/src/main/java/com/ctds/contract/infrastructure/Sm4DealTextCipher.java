package com.ctds.contract.infrastructure;

import com.ctds.common.crypto.Sm4Service;
import com.ctds.contract.domain.DealTextCipher;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 合约域文本加解密实现（WBS-3.4.3 hifi §6.1；CAT-04 L3 存储保密——SM4 经 common-crypto
 * 唯一入口 ADR-006，密码学零自实现红线 7）。keyRef = ctds.contract.deal-key-ref
 * （默认 contract-deal-text）；密钥源 = 本地密钥文件或 KMS 托管二选一（组件原生支持，
 * 沿 subject 证照材料先例）；未配置密钥源 → 加解密 fail-closed（1001S0001/S0002 组件原语义）。
 */
@Component
public class Sm4DealTextCipher implements DealTextCipher {

    private final Sm4Service sm4Service;
    private final String keyRef;

    public Sm4DealTextCipher(final Sm4Service sm4Service,
            @Value("${ctds.contract.deal-key-ref:contract-deal-text}") final String keyRef) {
        this.sm4Service = sm4Service;
        this.keyRef = keyRef;
    }

    @Override
    public byte[] encrypt(final String utf8Text) {
        return sm4Service.encrypt(utf8Text.getBytes(StandardCharsets.UTF_8), keyRef);
    }

    @Override
    public String decrypt(final byte[] cipher) {
        return new String(sm4Service.decrypt(cipher, keyRef), StandardCharsets.UTF_8);
    }
}
