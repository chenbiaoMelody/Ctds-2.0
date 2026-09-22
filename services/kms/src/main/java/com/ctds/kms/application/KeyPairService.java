package com.ctds.kms.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.crypto.Sm2KeyPair;
import com.ctds.common.crypto.Sm2Service;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.kms.domain.KeyAuditRecord;
import com.ctds.kms.domain.KeyPair;
import com.ctds.kms.domain.KeyRepository;
import com.ctds.kms.domain.KmsErrorCodes;
import com.ctds.kms.domain.KmsKeys;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SM2 密钥对托管应用服务（WBS-3.1.8，hifi §4.2）：生成密钥对（私钥 D 值经根密钥 SM4 信封落库，零明文）、
 * 内部签名（私钥只在本服务内解密后签名，不返回私钥材料）。算法一律经 common-crypto 唯一入口（红线：不自研密码学）。
 */
@Service
public class KeyPairService {

    private static final Logger log = LoggerFactory.getLogger(KeyPairService.class);
    private static final String KEY_TYPE_SM2 = "SM2";
    private static final String ACTION_CREATE_KEY_PAIR = "CREATE_KEY_PAIR";
    private static final int MAX_KEY_REF_CHARS = 64;
    private static final String KEY_REF_PATTERN = "[A-Za-z0-9._-]+";

    private final KeyRepository repository;
    private final Sm2Service sm2Service;
    private final Sm4Service sm4Service;

    public KeyPairService(final KeyRepository repository, final Sm2Service sm2Service,
            final Sm4Service sm4Service) {
        this.repository = repository;
        this.sm2Service = sm2Service;
        this.sm4Service = sm4Service;
    }

    /** 生成 SM2 密钥对：私钥 D 值信封落库、公钥出站；编号已存在 → 1002B0001。 */
    public KeyPairCreated create(final String keyRef) {
        requireKeyRef(keyRef);
        if (repository.exists(keyRef)) {
            throw new BizException(KmsErrorCodes.KMS_KEY_ALREADY_EXISTS, "密钥编号已存在");
        }
        final Sm2KeyPair pair = sm2Service.generateKeyPair();
        final LocalDateTime now = LocalDateTime.now();
        final String privateCipher = envelope(HexFormat.of().parseHex(pair.privateKeyHex()));
        repository.createKeyPair(new KeyPair(keyRef, KEY_TYPE_SM2, pair.publicKeyHex(), privateCipher, now),
                new KeyAuditRecord(ACTION_CREATE_KEY_PAIR, keyRef, null, 1, operator(), now));
        log.info("KMS SM2 key pair created: keyRef={}", keyRef);
        return new KeyPairCreated(keyRef, pair.publicKeyHex(), now);
    }

    /** 内部签名（私钥不出 KMS）：私钥 D 值在本服务内解密后签名，返回 DER 签名。 */
    public byte[] sign(final String keyRef, final byte[] data) {
        requireKeyRef(keyRef);
        if (data == null || data.length == 0) {
            throw new BizException(KmsErrorCodes.KMS_INPUT_INVALID, "待签名数据不能为空");
        }
        final KeyPair keyPair = repository.findKeyPair(keyRef)
                .orElseThrow(() -> new BizException(KmsErrorCodes.KMS_KEY_NOT_FOUND, "密钥编号不存在"));
        if (!KEY_TYPE_SM2.equals(keyPair.keyType())) {
            throw new BizException(KmsErrorCodes.KMS_INPUT_INVALID, "非 SM2 密钥不支持签名");
        }
        final String privateHex = HexFormat.of().formatHex(open(keyPair.privateCipher()));
        return sm2Service.sign(data, privateHex);
    }

    /** 私钥 D 值经根密钥 SM4 信封（Base64 落库，零明文）。 */
    private String envelope(final byte[] privateBytes) {
        return Base64.getEncoder().encodeToString(sm4Service.encrypt(privateBytes, KmsKeys.ROOT_KEY_REF));
    }

    /** 解开私钥 D 值信封（仅在签名路径内存使用，不出站）。 */
    private byte[] open(final String privateCipher) {
        return sm4Service.decrypt(Base64.getDecoder().decode(privateCipher), KmsKeys.ROOT_KEY_REF);
    }

    private static String operator() {
        final String subject = AuthContext.subject();
        return subject == null ? "anonymous" : subject;
    }

    private static String requireKeyRef(final String keyRef) {
        if (keyRef == null || keyRef.isBlank() || keyRef.length() > MAX_KEY_REF_CHARS
                || !keyRef.matches(KEY_REF_PATTERN)) {
            throw new BizException(KmsErrorCodes.KMS_INPUT_INVALID, "密钥编号不合法（仅允许字母数字 . _ -）");
        }
        return keyRef;
    }

    /** 密钥对创建结果视图（record：编号 + 公钥 + 时间）。 */
    public record KeyPairCreated(String keyRef, String publicKeyHex, LocalDateTime createdAt) {
    }
}
