package com.ctds.evidence.domain;

import com.ctds.common.crypto.Sm3Service;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 摘要规范化与上链载荷（hifi §3 封闭可复算口径；规格 §2 术语"内容摘要"）：
 *
 * <pre>
 * subjectDidDigest = SM3("CTDS-DID-1|" + subjectDid)
 * digestInput      = "CTDS-EV-1|" + stageType + "|" + businessNo + "|" + operationType
 *                    + "|" + occurredAtUtcIso + "|" + subjectDidDigest
 * contentDigest    = SM3(digestInput)
 * </pre>
 *
 * <p>字段值不做 trim / 大小写转换 / Unicode 归一；不加盐（Q5-A，保证第三方可独立复算）；
 * 入参只有六个业务字段，原文 / 个人信息 / 密钥材料结构性无法进入摘要。</p>
 */
public final class EvidenceDigests {

    /** 创世块前序哈希 = 64 个 0（hifi §3 本地账本哈希链）。 */
    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    private static final String DID_VERSION_PREFIX = "CTDS-DID-1|";
    private static final String EVIDENCE_VERSION_PREFIX = "CTDS-EV-1|";
    private static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private EvidenceDigests() {
    }

    /** 事件时点固定形态 yyyy-MM-dd'T'HH:mm:ss.SSS'Z'（UTC 三位毫秒）。 */
    public static String occurredAtIso(final Instant instant) {
        return UTC_ISO.format(instant);
    }

    /** 主体 DID 摘要（DID 原值不落库、不上链）。 */
    public static String subjectDidDigest(final Sm3Service sm3, final String subjectDid) {
        return sm3.digestHex(DID_VERSION_PREFIX + subjectDid);
    }

    /** 规范化摘要输入串（固定顺序、固定 {@code |} 分隔；可被第三方逐字复算）。 */
    public static String contentDigestInput(final EvidenceStageType stageType, final String businessNo,
            final EvidenceOperationType operationType, final Instant occurredAt,
            final String subjectDidDigest) {
        return EVIDENCE_VERSION_PREFIX + stageType.name() + "|" + businessNo + "|"
                + operationType.name() + "|" + occurredAtIso(occurredAt) + "|" + subjectDidDigest;
    }

    /** 内容摘要（SM3 唯一入口 common-crypto；64 位小写 hex）。 */
    public static String contentDigest(final Sm3Service sm3, final EvidenceStageType stageType,
            final String businessNo, final EvidenceOperationType operationType,
            final Instant occurredAt, final String subjectDidDigest) {
        return sm3.digestHex(contentDigestInput(stageType, businessNo, operationType, occurredAt,
                subjectDidDigest));
    }

    /**
     * 上链载荷 JSON（白名单五项、固定键序、无空白；服务端手工拼装，不经动态序列化）。
     * 字段值字符集封闭（单据号 / 枚举 / hex / ISO 时间），无需转义。
     */
    public static String payloadJson(final String businessNo, final Instant occurredAt,
            final EvidenceOperationType operationType, final String subjectDidDigest,
            final String contentDigest) {
        return "{\"businessNo\":\"" + businessNo + "\","
                + "\"occurredAt\":\"" + occurredAtIso(occurredAt) + "\","
                + "\"operationType\":\"" + operationType.name() + "\","
                + "\"subjectDidDigest\":\"" + subjectDidDigest + "\","
                + "\"contentDigest\":\"" + contentDigest + "\"}";
    }

    /** 账本块哈希 = SM3(块号|载荷摘要|载荷|前序哈希|创建时点 UTC ISO)。 */
    public static String blockHash(final Sm3Service sm3, final long blockNo, final String payloadDigest,
            final String payloadJson, final String prevBlockHash, final Instant createdAt) {
        return sm3.digestHex(blockNo + "|" + payloadDigest + "|" + payloadJson + "|"
                + prevBlockHash + "|" + occurredAtIso(createdAt));
    }

    /** 交易标识 = LTX + 12 位块号补零。 */
    public static String chainTxId(final long blockNo) {
        return String.format("LTX%012d", blockNo);
    }
}
