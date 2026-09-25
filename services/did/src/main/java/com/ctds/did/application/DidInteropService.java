package com.ctds.did.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.InteropDirection;
import com.ctds.did.domain.InteropLog;
import com.ctds.did.domain.InteropLogRepository;
import com.ctds.did.domain.VerificationOutcome;
import com.ctds.did.domain.VerificationReason;
import com.ctds.std.did.DidInteropSamples;
import com.ctds.std.did.DidInteropStandardApi;
import com.ctds.std.did.InteropClaim;
import com.ctds.std.did.InteropReason;
import com.ctds.std.did.InteropResult;
import com.ctds.std.did.InteropVerification;
import java.time.LocalDateTime;
import java.util.Base64;
import org.springframework.stereotype.Service;

/**
 * 跨空间身份互认应用服务（WBS-3.1.10 行为 5）：本服务只做"输入校验 → 经互认域取结论 → 结论映射与留痕"，
 * 三查口径与协议逻辑一律由 std-adapter 的互认域执行（收口纪律 ADR-008 §3.1，业务服务不得自实现）。
 * <p>留痕口径（hifi §6）：业务结论（通过 / 不通过 / 不可用）一律留痕四要素 + 原因；
 * <b>输入类拒绝与内部错误不留痕</b>。</p>
 */
@Service
public class DidInteropService {

    private static final String DID_PREFIX = "did:ctds:";
    /** 原文上限（沿 3.1.9 验证口径）。 */
    private static final int MAX_DATA_BYTES = 1024 * 1024;
    /** 签名上限（SM2 DER 签名约 70~72 字节；沿 3.1.9 验证口径）。 */
    private static final int MAX_SIGNATURE_BYTES = 512;
    /** 对端空间标识长度上限（与留痕表列宽对齐，超长先拒，避免落库异常）。 */
    private static final int MAX_PEER_SPACE_CHARS = 64;
    /** DID 长度上限（与 DID 注册表/留痕表列宽对齐）。 */
    private static final int MAX_DID_CHARS = 128;

    private final DidInteropStandardApi interop;
    private final InteropLogRepository interopLogRepository;

    public DidInteropService(final DidInteropStandardApi interop,
            final InteropLogRepository interopLogRepository) {
        this.interop = interop;
        this.interopLogRepository = interopLogRepository;
    }

    /** 来访验证（行为 5 规则 1/2）：对端主体凭对端 DID + 签名来访，三查按对端口径执行。 */
    public InteropVerificationResult verifyInbound(final String peerSpace, final String did, final String dataBase64,
            final String signatureBase64) {
        requirePeerSpace(peerSpace);
        requirePeerDid(did);
        final byte[] data = decode(dataBase64, MAX_DATA_BYTES);
        final byte[] signature = decode(signatureBase64, MAX_SIGNATURE_BYTES);
        return conclude(InteropDirection.INBOUND, callInbound(new InteropClaim(peerSpace, did, data, signature)));
    }

    /** 出向验证（行为 5 验收标准 3 双向口径）：结论由模拟对端回放本空间 DID 当前状态。 */
    public InteropVerificationResult verifyOutbound(final String did) {
        if (did == null || did.isBlank() || did.length() > MAX_DID_CHARS || !did.startsWith(DID_PREFIX)) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID, "互认参数不合法");
        }
        return conclude(InteropDirection.OUTBOUND, callOutbound(did));
    }

    /** 预置样例清单（剧本附录 A 组 E1 取用口径；样例资源不可读属内部错误）。 */
    public DidInteropSamples samples() {
        try {
            return interop.samples();
        } catch (final RuntimeException e) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INTERNAL_ERROR, "互认内部错误", e);
        }
    }

    private InteropVerification callInbound(final InteropClaim claim) {
        try {
            return interop.verifyInbound(claim);
        } catch (final RuntimeException e) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INTERNAL_ERROR, "互认内部错误", e);
        }
    }

    private InteropVerification callOutbound(final String did) {
        try {
            return interop.verifyOutbound(did);
        } catch (final RuntimeException e) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INTERNAL_ERROR, "互认内部错误", e);
        }
    }

    /** 结论映射 + 应用时钟留痕（ADR-017 §2.2：时间戳经应用时钟秒级写入）。 */
    private InteropVerificationResult conclude(final InteropDirection direction,
            final InteropVerification verification) {
        final VerificationOutcome outcome = outcomeOf(verification.result());
        final VerificationReason reason = reasonOf(verification.reason());
        final LocalDateTime now = LocalDateTime.now().withNano(0);
        interopLogRepository.insert(
                new InteropLog(direction, verification.peerSpace(), verification.did(), outcome, reason, now));
        return new InteropVerificationResult(verification.peerSpace(), verification.did(), outcome, reason, now);
    }

    private static VerificationOutcome outcomeOf(final InteropResult result) {
        return switch (result) {
            case PASS -> VerificationOutcome.PASS;
            case FAIL -> VerificationOutcome.FAIL;
            case UNAVAILABLE -> VerificationOutcome.UNAVAILABLE;
        };
    }

    private static VerificationReason reasonOf(final InteropReason reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case SIGNATURE_INVALID -> VerificationReason.SIGNATURE_INVALID;
            case REVOKED -> VerificationReason.REVOKED;
            case SUBJECT_BINDING_FAILED -> VerificationReason.SUBJECT_BINDING_FAILED;
            case NOT_REGISTERED -> VerificationReason.NOT_REGISTERED;
            case BINDING_UNAVAILABLE -> VerificationReason.BINDING_UNAVAILABLE;
        };
    }

    private static void requirePeerSpace(final String peerSpace) {
        if (peerSpace == null || peerSpace.isBlank() || peerSpace.length() > MAX_PEER_SPACE_CHARS) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID, "互认参数不合法");
        }
    }

    private static void requirePeerDid(final String did) {
        if (did == null || did.isBlank() || did.length() > MAX_DID_CHARS) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID, "互认参数不合法");
        }
    }

    /** 传输层 Base64 解码（沿 3.1.9 验证口径：空/非法/空内容/超上限一律输入类拒绝）。 */
    private static byte[] decode(final String base64, final int maxBytes) {
        if (base64 == null || base64.isBlank()) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID, "互认参数不合法");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64.trim());
        } catch (final IllegalArgumentException e) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID, "互认参数不合法");
        }
        if (decoded.length == 0 || decoded.length > maxBytes) {
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID, "互认参数不合法");
        }
        return decoded;
    }

    /** 互认验证结果视图（对端空间标识 / DID / 结论 / 原因（PASS 时为空）/ 时间）。 */
    public record InteropVerificationResult(
            String peerSpace,
            String did,
            VerificationOutcome result,
            VerificationReason reason,
            LocalDateTime verifiedAt) {
    }
}
