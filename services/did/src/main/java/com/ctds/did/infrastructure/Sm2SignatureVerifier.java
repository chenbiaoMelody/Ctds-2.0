package com.ctds.did.infrastructure;

import com.ctds.common.crypto.Sm2Service;
import com.ctds.did.domain.SignatureVerifier;
import org.springframework.stereotype.Component;

/**
 * 验签适配（WBS-3.1.9 hifi S5）：经 common-crypto 唯一入口（红线：不自研密码学）。
 * 行为口径见 Sm2Service.verify：数据/签名被改或公钥不匹配 → false；密钥/数据异常 → 抛出（调用方收敛 1005S0002）。
 */
@Component
public class Sm2SignatureVerifier implements SignatureVerifier {

    private final Sm2Service sm2Service;

    public Sm2SignatureVerifier(final Sm2Service sm2Service) {
        this.sm2Service = sm2Service;
    }

    @Override
    public boolean verify(final byte[] data, final byte[] signature, final String publicKeyHex) {
        return sm2Service.verify(data, signature, publicKeyHex);
    }
}