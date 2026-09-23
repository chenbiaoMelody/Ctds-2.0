package com.ctds.did.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.DidIdentity;
import com.ctds.did.domain.DidRepository;
import com.ctds.did.domain.DidStatus;
import com.ctds.did.domain.SignatureVerifier;
import com.ctds.did.domain.SubjectAdmission;
import com.ctds.did.domain.SubjectStatusPort;
import com.ctds.did.domain.VerificationLog;
import com.ctds.did.domain.VerificationOutcome;
import com.ctds.did.domain.VerificationReason;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * DID 验证应用服务（WBS-3.1.9 行为 3）：三查（签名核验 → 状态核验 → 主体绑定核验）逐一判定，
 * 任一不满足 → FAIL + 明确原因；绑定核验服务不可用 → UNAVAILABLE（系统态不冒充"不通过"）。
 * 每次验证留痕（时间/DID/结果，失败另含原因；不保存业务数据原文）。验证只证明身份，不含授权（规则 4）。
 */
@Service
public class DidVerificationService {

    private static final String DID_PREFIX = "did:ctds:";
    /** 待验证数据上限（防超大输入；1MB 沿 common-crypto 输入上限口径）。 */
    private static final int MAX_DATA_BYTES = 1024 * 1024;
    /** 签名上限（SM2 DER 签名约 70~72 字节；512 留足余量并拒绝异常输入）。 */
    private static final int MAX_SIGNATURE_BYTES = 512;

    private final DidRepository repository;
    private final SignatureVerifier signatureVerifier;
    private final SubjectStatusPort subjectStatusPort;

    public DidVerificationService(final DidRepository repository, final SignatureVerifier signatureVerifier,
            final SubjectStatusPort subjectStatusPort) {
        this.repository = repository;
        this.signatureVerifier = signatureVerifier;
        this.subjectStatusPort = subjectStatusPort;
    }

    /** 验证：三查判定 + 留痕；返回结论（PASS / FAIL+原因 / UNAVAILABLE）。 */
    public VerificationResult verify(final String did, final String dataBase64, final String signatureBase64) {
        requireDid(did);
        final byte[] data = decode(dataBase64, MAX_DATA_BYTES);
        final byte[] signature = decode(signatureBase64, MAX_SIGNATURE_BYTES);

        final Optional<DidIdentity> found = repository.findByDid(did);
        if (found.isEmpty()) {
            return conclude(did, VerificationOutcome.FAIL, VerificationReason.NOT_REGISTERED);
        }
        final DidIdentity identity = found.get();

        // 三查①签名核验（公钥取注册表 130-hex；验签异常 = 库内数据/密钥问题 → 内部错误，不冒充输入失败）
        final boolean signatureOk;
        try {
            signatureOk = signatureVerifier.verify(data, signature, identity.publicKeyHex());
        } catch (final RuntimeException e) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INTERNAL_ERROR, "验证内部错误");
        }
        if (!signatureOk) {
            return conclude(did, VerificationOutcome.FAIL, VerificationReason.SIGNATURE_INVALID);
        }

        // 三查②状态核验
        if (identity.status() != DidStatus.ACTIVE) {
            return conclude(did, VerificationOutcome.FAIL, VerificationReason.REVOKED);
        }

        // 三查③主体绑定核验（实时查询主体服务；不可用如实表征）
        final SubjectAdmission admission = subjectStatusPort.check(identity.subjectNo());
        return switch (admission) {
            case ADMITTED -> conclude(did, VerificationOutcome.PASS, null);
            case NOT_ADMITTED -> conclude(did, VerificationOutcome.FAIL, VerificationReason.SUBJECT_BINDING_FAILED);
            case UNAVAILABLE -> conclude(did, VerificationOutcome.UNAVAILABLE,
                    VerificationReason.BINDING_UNAVAILABLE);
        };
    }

    private VerificationResult conclude(final String did, final VerificationOutcome outcome,
            final VerificationReason reason) {
        final LocalDateTime now = LocalDateTime.now().withNano(0);
        repository.insertVerificationLog(new VerificationLog(did, outcome, reason, now));
        return new VerificationResult(did, outcome, reason, now);
    }

    private static void requireDid(final String did) {
        if (did == null || did.isBlank() || !did.startsWith(DID_PREFIX)) {
            throw new BizException(DidErrorCodes.DID_PARAM_INVALID, "DID 标识不合法");
        }
    }

    private static byte[] decode(final String base64, final int maxBytes) {
        if (base64 == null || base64.isBlank()) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID, "验证参数不合法");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64.trim());
        } catch (final IllegalArgumentException e) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID, "验证参数不合法");
        }
        if (decoded.length == 0 || decoded.length > maxBytes) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID, "验证参数不合法");
        }
        return decoded;
    }

    /** 验证结果视图（record：DID/结论/原因（PASS 时为空）/时间）。 */
    public record VerificationResult(String did, VerificationOutcome result, VerificationReason reason,
            LocalDateTime verifiedAt) {
    }
}