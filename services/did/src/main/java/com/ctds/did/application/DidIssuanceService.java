package com.ctds.did.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.errorcode.BizException;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.DidIdentity;
import com.ctds.did.domain.DidKmsClient;
import com.ctds.did.domain.DidOperationLog;
import com.ctds.did.domain.DidRepository;
import com.ctds.did.domain.DidStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * DID 签发应用服务（WBS-3.1.8，规格 C-1.2 行为 1/4）：自动签发（幂等 + 失败可重试）、
 * 重试、重签、吊销。私钥零明文：本服务只经 KMS 取公钥，私钥不出 KMS（行为 1 规则 3）。
 */
@Service
public class DidIssuanceService {

    private static final Logger log = LoggerFactory.getLogger(DidIssuanceService.class);
    private static final String OPERATION_ISSUE = "ISSUE";
    private static final String OPERATION_REISSUE = "REISSUE";
    private static final String OPERATION_REVOKE = "REVOKE";
    private static final String SYSTEM_OPERATOR = "SYSTEM";
    private static final String DID_PREFIX = "did:ctds:";
    private static final String KEY_REF_PREFIX = "did-";
    private static final int MAX_SUBJECT_NO_CHARS = 32;
    private static final int MAX_REASON_CHARS = 256;

    private final DidRepository repository;
    private final DidKmsClient kmsClient;
    private final ObjectMapper objectMapper;

    public DidIssuanceService(final DidRepository repository, final DidKmsClient kmsClient,
            final ObjectMapper objectMapper) {
        this.repository = repository;
        this.kmsClient = kmsClient;
        this.objectMapper = objectMapper;
    }

    /** 自动签发（入驻联动触发，操作者 SYSTEM）：幂等；KMS 失败 → PENDING_ISSUE 可重试。 */
    public IssuanceResult issue(final String subjectNo) {
        requireSubjectNo(subjectNo);
        final Optional<DidIdentity> existing = repository.findActiveOrPending(subjectNo);
        if (existing.isPresent()) {
            final DidIdentity identity = existing.get();
            if (identity.status() == DidStatus.ACTIVE) {
                return IssuanceResult.active(identity.did(), identity.keyRef(), identity.updatedAt());
            }
            return completePending(identity, OPERATION_ISSUE, SYSTEM_OPERATOR);
        }
        final DidIdentity pending = repository.createPending(subjectNo, repository.nextIssuanceSeq(subjectNo),
                now());
        return completePending(pending, OPERATION_ISSUE, SYSTEM_OPERATOR);
    }

    /** 运营重试（did.admin）：无待签发记录 → 1005B0001。 */
    public IssuanceResult retry(final String subjectNo) {
        requireSubjectNo(subjectNo);
        final DidIdentity pending = repository.findPending(subjectNo)
                .orElseThrow(() -> new BizException(DidErrorCodes.DID_NO_PENDING_ISSUANCE, "未找到待签发记录"));
        return completePending(pending, OPERATION_ISSUE, operator());
    }

    /** 运营重签（did.admin）：无已吊销记录 → 1005B0002；新序号 + 全新密钥对 + 新 did，旧记录保留。 */
    public IssuanceResult reissue(final String subjectNo) {
        requireSubjectNo(subjectNo);
        repository.findLatestRevoked(subjectNo)
                .orElseThrow(() -> new BizException(DidErrorCodes.DID_NO_REVOKED_TO_REISSUE, "无已吊销记录可重签"));
        final int issuanceSeq = repository.nextIssuanceSeq(subjectNo);
        final DidIdentity pending = repository.createPending(subjectNo, issuanceSeq, now());
        return completePending(pending, OPERATION_REISSUE, operator());
    }

    /** 吊销（did.admin）：理由必填、非有效 DID 不可吊销；即时生效、不可逆（不提供恢复端点）。 */
    public RevocationResult revoke(final String did, final String reason) {
        if (did == null || did.isBlank()) {
            throw new BizException(DidErrorCodes.DID_PARAM_INVALID, "DID 标识不合法");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException(DidErrorCodes.DID_REVOKE_REASON_REQUIRED, "吊销理由必填");
        }
        if (reason.length() > MAX_REASON_CHARS) {
            throw new BizException(DidErrorCodes.DID_PARAM_INVALID,
                    "吊销理由长度不能超过" + MAX_REASON_CHARS + "字");
        }
        final DidIdentity identity = repository.findByDid(did)
                .filter(d -> d.status() == DidStatus.ACTIVE)
                .orElseThrow(() -> new BizException(DidErrorCodes.DID_REVOKE_NOT_ACTIVE, "非有效 DID 不可吊销"));
        final LocalDateTime now = now();
        repository.revoke(identity.id(), new DidOperationLog(did, identity.subjectNo(), OPERATION_REVOKE,
                operator(), reason, null, DidStatus.ACTIVE.name(), DidStatus.REVOKED.name(), now));
        return new RevocationResult(did, DidStatus.REVOKED, now);
    }

    private IssuanceResult completePending(final DidIdentity pending, final String operation,
            final String operator) {
        final String keyRef = keyRefFor(pending);
        final String publicKeyHex;
        try {
            publicKeyHex = kmsClient.createKeyPair(keyRef);
        } catch (final RuntimeException e) {
            // 并发窗口：密钥已被并发请求创建（KMS 键号已存在）且行已由并发转有效 → 幂等返回既有结果
            final Optional<DidIdentity> active = activeIdentity(pending.subjectNo());
            if (active.isPresent()) {
                return IssuanceResult.active(active.get().did(), active.get().keyRef(), active.get().updatedAt());
            }
            log.warn("DID 签发失败（KMS 不可达等），记录停留待签发: subjectNo={}", pending.subjectNo(), e);
            return IssuanceResult.pending();
        }
        final LocalDateTime now = now();
        final String did = didOf(pending.subjectNo(), pending.issuanceSeq());
        final String documentJson = buildDocument(did, pending.subjectNo(), publicKeyHex, now);
        final boolean completed = repository.completeIssuance(pending.id(), publicKeyHex, documentJson,
                new DidOperationLog(did, pending.subjectNo(), operation, operator, null, keyRef,
                        DidStatus.PENDING_ISSUE.name(), DidStatus.ACTIVE.name(), now));
        if (!completed) {
            // 并发窗口：行已被并发请求完成（乐观门槛 0 行）→ 幂等返回既有 ACTIVE 结果，不落重复留痕
            final DidIdentity active = activeIdentity(pending.subjectNo())
                    .orElseThrow(() -> new BizException(DidErrorCodes.DID_ISSUANCE_INTERNAL_ERROR,
                            "签发处理失败，请重试"));
            return IssuanceResult.active(active.did(), active.keyRef(), active.updatedAt());
        }
        return IssuanceResult.active(did, keyRef, now);
    }

    /** 主体当前有效 DID（幂等收敛回查；无有效行返回空）。 */
    private Optional<DidIdentity> activeIdentity(final String subjectNo) {
        return repository.findActiveOrPending(subjectNo).filter(i -> i.status() == DidStatus.ACTIVE);
    }

    private String buildDocument(final String did, final String subjectNo, final String publicKeyHex,
            final LocalDateTime created) {
        final Map<String, Object> publicKey = new LinkedHashMap<>();
        publicKey.put("type", "SM2");
        publicKey.put("algorithm", "sm2p256v1");
        publicKey.put("valueHex", publicKeyHex);
        final Map<String, Object> service = new LinkedHashMap<>();
        service.put("id", "#resolution");
        service.put("type", "DidResolution");
        service.put("serviceEndpoint", "/api/v1/did");
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put("did", did);
        document.put("publicKey", publicKey);
        document.put("controller", subjectNo);
        document.put("service", List.of(service));
        document.put("created", created.toString());
        try {
            return objectMapper.writeValueAsString(document);
        } catch (final JsonProcessingException e) {
            throw new BizException(DidErrorCodes.DID_ISSUANCE_INTERNAL_ERROR, "DID 文档组装失败");
        }
    }

    private static String didOf(final String subjectNo, final int issuanceSeq) {
        return DID_PREFIX + subjectNo + "." + issuanceSeq;
    }

    private static String keyRefFor(final DidIdentity identity) {
        return KEY_REF_PREFIX + identity.subjectNo() + "-" + identity.issuanceSeq();
    }

    private static String operator() {
        final String subject = AuthContext.subject();
        return subject == null ? "anonymous" : subject;
    }

    /** 应用时钟（截断到秒：DID 文档与留痕的时间格式固定为秒级 ISO-8601，hifi §2.3 示例口径）。 */
    private static LocalDateTime now() {
        return LocalDateTime.now().withNano(0);
    }

    private static String requireSubjectNo(final String subjectNo) {
        if (subjectNo == null || subjectNo.isBlank() || subjectNo.length() > MAX_SUBJECT_NO_CHARS) {
            throw new BizException(DidErrorCodes.DID_PARAM_INVALID, "主体申请编号不合法");
        }
        return subjectNo;
    }

    /** 签发结果视图（record：did/状态/密钥引用/签发时间；PENDING 时 did/keyRef/issuedAt 为空）。 */
    public record IssuanceResult(String did, DidStatus status, String keyRef, LocalDateTime issuedAt) {

        public static IssuanceResult active(final String did, final String keyRef, final LocalDateTime issuedAt) {
            return new IssuanceResult(did, DidStatus.ACTIVE, keyRef, issuedAt);
        }

        public static IssuanceResult pending() {
            return new IssuanceResult(null, DidStatus.PENDING_ISSUE, null, null);
        }
    }

    /** 吊销结果视图（record）。 */
    public record RevocationResult(String did, DidStatus status, LocalDateTime revokedAt) {
    }
}
