package com.ctds.evidence.support;

import com.ctds.evidence.domain.EvidenceActionLog;
import com.ctds.evidence.domain.EvidenceOperationType;
import com.ctds.evidence.domain.EvidencePage;
import com.ctds.evidence.domain.EvidenceRecord;
import com.ctds.evidence.domain.EvidenceRepository;
import com.ctds.evidence.domain.EvidenceSearchCriteria;
import com.ctds.evidence.domain.EvidenceStageType;
import com.ctds.evidence.domain.EvidenceStatus;
import com.ctds.evidence.infrastructure.JdbcEvidenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;

/**
 * 幂等并发兜底的分支构造（P2-2 修复批）：预检模拟"并发窗口未命中"（返回空一次），
 * 随后 insert 抛唯一键冲突，迫使 {@code DuplicateKeyException → 回查} 分支被执行。
 *
 * <p>选择确定性替身而非真实双线程：唯一键冲突时序受 JVM 调度与连接池影响，双线程用例易 flaky；
 * 分支语义（回查既有记录 + duplicate=true + 零新增）与真实并发路径一致。</p>
 */
@TestConfiguration
public class RacingRepositoryConfiguration {

    /** 下一次幂等预检返回空（消费一次即复位）。 */
    public static final AtomicBoolean PRECHECK_MISS_ONCE = new AtomicBoolean(false);

    /** 下一次 insert 抛唯一键冲突（消费一次即复位）。 */
    public static final AtomicBoolean DUPLICATE_INSERT_ONCE = new AtomicBoolean(false);

    @Bean
    @Primary
    public EvidenceRepository racingEvidenceRepository(final JdbcEvidenceRepository delegate) {
        return new EvidenceRepository() {
            @Override
            public void insert(final EvidenceRecord record) {
                if (DUPLICATE_INSERT_ONCE.compareAndSet(true, false)) {
                    throw new DuplicateKeyException("测试构造：uk_evidence_idem 并发冲突");
                }
                delegate.insert(record);
            }

            @Override
            public void updateStatus(final EvidenceRecord record) {
                delegate.updateStatus(record);
            }

            @Override
            public void appendAction(final EvidenceActionLog action) {
                delegate.appendAction(action);
            }

            @Override
            public Optional<EvidenceRecord> findByNo(final String evidenceNo) {
                return delegate.findByNo(evidenceNo);
            }

            @Override
            public Optional<EvidenceRecord> findByIdempotentKey(final EvidenceStageType stageType,
                    final String businessNo, final EvidenceOperationType operationType) {
                if (PRECHECK_MISS_ONCE.compareAndSet(true, false)) {
                    return Optional.empty();
                }
                return delegate.findByIdempotentKey(stageType, businessNo, operationType);
            }

            @Override
            public EvidencePage page(final EvidenceSearchCriteria criteria, final int offset,
                    final int limit) {
                return delegate.page(criteria, offset, limit);
            }

            @Override
            public long countByStatus(final EvidenceStatus status) {
                return delegate.countByStatus(status);
            }

            @Override
            public Optional<Instant> lastFailedAt() {
                return delegate.lastFailedAt();
            }

            @Override
            public List<EvidenceActionLog> findActions(final String evidenceNo) {
                return delegate.findActions(evidenceNo);
            }
        };
    }
}
