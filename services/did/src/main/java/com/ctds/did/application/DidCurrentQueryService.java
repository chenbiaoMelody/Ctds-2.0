package com.ctds.did.application;

import com.ctds.did.domain.DidIdentity;
import com.ctds.did.domain.DidRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 主体当前 DID 只读查询（WBS-3.6.3 需求，ADR-017 补记内部面）：
 * 取该主体**最新非吊销行**（有效 / 待签发）；无则取最新已吊销行；完全无记录 = NONE。
 * 只返回最小三字段（subjectNo / did / status），不暴露文档、公钥与密钥引用。
 */
@Service
public class DidCurrentQueryService {

    private static final String STATUS_NONE = "NONE";

    private final DidRepository repository;

    public DidCurrentQueryService(final DidRepository repository) {
        this.repository = repository;
    }

    /** 当前 DID 视图（无记录时 did 为 null、status = NONE）。 */
    public CurrentDidView current(final String subjectNo) {
        final Optional<DidIdentity> active = repository.findActiveOrPending(subjectNo);
        if (active.isPresent()) {
            final DidIdentity identity = active.get();
            return new CurrentDidView(subjectNo, identity.did(), identity.status().name());
        }
        return repository.findLatestRevoked(subjectNo)
                .map(identity -> new CurrentDidView(subjectNo, identity.did(), "REVOKED"))
                .orElseGet(() -> new CurrentDidView(subjectNo, null, STATUS_NONE));
    }

    /** 内部面最小暴露视图。 */
    public record CurrentDidView(String subjectNo, String did, String status) {
    }
}
