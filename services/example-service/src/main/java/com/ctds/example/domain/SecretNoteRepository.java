package com.ctds.example.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 领域仓储接口（依赖倒置，实现在基础设施层）。
 */
public interface SecretNoteRepository {

    SecretNote save(SecretNote note);

    Optional<SecretNote> findById(UUID id);

    /** 演示用：覆盖存储的信封（模拟落库后被篡改）。 */
    void replaceEnvelope(UUID id, String envelope);
}
