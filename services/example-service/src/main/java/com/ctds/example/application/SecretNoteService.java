package com.ctds.example.application;

import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.example.domain.SecretNote;
import com.ctds.example.domain.SecretNoteRepository;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 应用服务：保密备注的加解密编排（WBS 2.4.6 B11 演示）。
 * 全链路只经 common-crypto 统一入口，业务代码不接触密钥材料与算法（hifi 流程第 2 步口径）。
 */
@Service
public class SecretNoteService {

    /** 演示密钥编号（真实部署：ctds.crypto.local.key-file 或 2.6.3 KMS 下发）。 */
    public static final String KEY_REF = "demo-note-key";

    private final SecretNoteRepository repository;
    private final Sm4Service sm4Service;

    public SecretNoteService(final SecretNoteRepository repository, final Sm4Service sm4Service) {
        this.repository = repository;
        this.sm4Service = sm4Service;
    }

    /** 明文 → SM4 加密 → 存信封；返回新记录。 */
    public SecretNote create(final String plainText) {
        return repository.save(SecretNote.of(sm4Service.encryptText(plainText, KEY_REF)));
    }

    /** 读回：解密；密文被篡改 → 组件抛 CRYPTO_DATA_REJECTED（B2 拒绝面）。 */
    public String read(final UUID id) {
        return sm4Service.decryptText(found(id).envelope(), KEY_REF);
    }

    /** 演示面：直接查看"库里存的密文"（不解密）。 */
    public String storedEnvelope(final UUID id) {
        return found(id).envelope();
    }

    /** 演示用：翻转已存信封中部一个比特（模拟落库后被篡改，验证拒绝面）。 */
    public void demoTamper(final UUID id) {
        final String envelope = found(id).envelope();
        final byte[] raw = Base64.getDecoder().decode(envelope);
        raw[raw.length / 2] ^= 0x01;
        repository.replaceEnvelope(id, Base64.getEncoder().encodeToString(raw));
    }

    private SecretNote found(final UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "资源不存在"));
    }
}
