package com.ctds.did.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.DidIdentity;
import com.ctds.did.domain.DidKmsClient;
import com.ctds.did.domain.DidRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 演示签名入口应用服务（规格 C-1.2 §6 第 6 条「平台侧代签边界」，WBS-3.1.11 B12）：
 * **仅演示/调试期**——真实业务签名由主体侧发起，生产环境禁用该入口（配置门槛默认关闭，
 * 关闭时复用既有 1000C0003，不新增错误码、不新增库表）。
 * 私钥零明文：签名一律经 KMS 内部签名面（ADR-017 §2.6），本服务不接触任何密钥材料；
 * 原文不入库、不落日志（仅 WARN 级记录 DID + 密钥引用 + 结果）。
 */
@Service
public class DidDemoSignatureService {

    private static final Logger log = LoggerFactory.getLogger(DidDemoSignatureService.class);
    private static final int MAX_DATA_CHARS = 1024;

    private final DidRepository repository;
    private final DidKmsClient kmsClient;
    private final boolean enabled;

    public DidDemoSignatureService(final DidRepository repository, final DidKmsClient kmsClient,
            @Value("${ctds.did.demo-signature.enabled:false}") final boolean enabled) {
        this.repository = repository;
        this.kmsClient = kmsClient;
        this.enabled = enabled;
    }

    /** 演示代签：入口启用校验 → 原文校验 → 记录存在性/密钥引用校验 → KMS 签名。 */
    public DemoSignatureResult sign(final String did, final String data) {
        // 入口门槛先行：关闭态不区分目标是否存在（不构成 DID 状态枚举通道），且不产生任何 KMS 调用
        if (!enabled) {
            throw new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "演示签名入口未启用（仅演示/调试期）");
        }
        final byte[] plaintext = requireData(data);
        final DidIdentity identity = repository.findByDid(did)
                .orElseThrow(() -> new BizException(DidErrorCodes.DID_NOT_REGISTERED, "该 DID 未登记"));
        if (identity.keyRef() == null || identity.keyRef().isBlank()) {
            // 未完成签发（记录中间态，无密钥引用）：复用 1005B0001 语义（未找到可操作的签发记录）
            throw new BizException(DidErrorCodes.DID_NO_PENDING_ISSUANCE,
                    "该主体 DID 尚未完成签发，无法生成演示签名");
        }
        final String dataBase64 = Base64.getEncoder().encodeToString(plaintext);
        final String signature;
        try {
            signature = kmsClient.sign(identity.keyRef(), dataBase64);
        } catch (final RuntimeException e) {
            log.warn("演示签名失败（签名服务不可用）: did={}, keyRef={}", did, identity.keyRef());
            throw new BizException(DidErrorCodes.DID_VERIFICATION_INTERNAL_ERROR, "签名服务暂不可用，请稍后重试");
        }
        log.warn("演示签名入口被使用（仅演示/调试期，生产禁用）: did={}, keyRef={}", did, identity.keyRef());
        return new DemoSignatureResult(did, dataBase64, signature, LocalDateTime.now().withNano(0));
    }

    /** 待签原文校验（1~1024 字符）：空/空白、超限 → 1005C0001。 */
    private static byte[] requireData(final String data) {
        if (data == null || data.isBlank()) {
            throw new BizException(DidErrorCodes.DID_PARAM_INVALID, "待签内容不能为空");
        }
        if (data.length() > MAX_DATA_CHARS) {
            throw new BizException(DidErrorCodes.DID_PARAM_INVALID, "待签内容长度不能超过1024字符");
        }
        return data.getBytes(StandardCharsets.UTF_8);
    }

    /** 演示签名结果（原文 Base64 + SM2 DER 签名 Base64 + 演示期时间）。 */
    public record DemoSignatureResult(String did, String data, String signature, LocalDateTime signedAt) {
    }
}
