package com.ctds.common.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * 幂等状态存储抽象（契约 = WBS-2.4.7-hifi）：执行权（首次/重复）与结果缓存的读写。
 * 双实现：Redis（默认，production 语义）/ 内存（单机/演示/单测兜底）；实现须满足契约测试
 * （IdempotencyStoreContractTest）的全部断言，业务零改动可替换（@ConditionalOnMissingBean）。
 */
public interface IdempotencyStore {

    /**
     * 尝试获取幂等键执行权：仅首次成功（并发同键恰好一个成功）；ttl = 执行中标记有效期。
     *
     * @param fullKey 组装后的完整幂等键（前缀 + 业务键）
     * @param ttl     执行中标记 TTL
     * @return true = 首次请求（获得执行权）
     */
    boolean tryAcquire(String fullKey, Duration ttl);

    /**
     * 业务成功：写入结果并置完成态（结果 TTL = 结果有效期；执行中标记同步延长，
     * 保证结果有效期内同键仍"已占用"，不会重复执行）。
     */
    void complete(String fullKey, String resultJson, Duration ttl);

    /** 读结果（仅完成态）；未完成 = empty。 */
    Optional<String> getResult(String fullKey);

    /** 业务失败：释放执行权（删除执行中标记与结果），允许同键重试。 */
    void release(String fullKey);
}
