package com.ctds.did.support;

import com.ctds.did.domain.DidIdentity;
import com.ctds.did.domain.DidOperationLog;
import com.ctds.did.domain.DidRepository;
import com.ctds.did.domain.VerificationLog;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DID 仓储测试桩（WBS-3.1.9 单元测试共享）：只实现解析/验证所需的查询与留痕落库；
 * 其余方法调用即抛 UnsupportedOperationException（显式暴露误用，不静默通过）。
 */
public final class DidRepositoryStub implements DidRepository {

    private final Map<String, DidIdentity> byDid = new LinkedHashMap<>();
    private final List<VerificationLog> verificationLogs = new ArrayList<>();

    /** 预置一条已登记身份（按 did 查询命中）。 */
    public void put(final DidIdentity identity) {
        byDid.put(identity.did(), identity);
    }

    public List<VerificationLog> verificationLogs() {
        return List.copyOf(verificationLogs);
    }

    @Override
    public Optional<DidIdentity> findByDid(final String did) {
        return Optional.ofNullable(byDid.get(did));
    }

    @Override
    public void insertVerificationLog(final VerificationLog log) {
        verificationLogs.add(log);
    }

    @Override
    public Optional<DidIdentity> findActiveOrPending(final String subjectNo) {
        throw new UnsupportedOperationException("单元测试未使用");
    }

    @Override
    public Optional<DidIdentity> findPending(final String subjectNo) {
        throw new UnsupportedOperationException("单元测试未使用");
    }

    @Override
    public Optional<DidIdentity> findLatestRevoked(final String subjectNo) {
        throw new UnsupportedOperationException("单元测试未使用");
    }

    @Override
    public DidIdentity createPending(final String subjectNo, final int issuanceSeq, final LocalDateTime now) {
        throw new UnsupportedOperationException("单元测试未使用");
    }

    @Override
    public boolean completeIssuance(final long identityId, final String publicKeyHex, final String documentJson,
            final DidOperationLog operationLog) {
        throw new UnsupportedOperationException("单元测试未使用");
    }

    @Override
    public int nextIssuanceSeq(final String subjectNo) {
        throw new UnsupportedOperationException("单元测试未使用");
    }

    @Override
    public void revoke(final long identityId, final DidOperationLog operationLog) {
        throw new UnsupportedOperationException("单元测试未使用");
    }
}