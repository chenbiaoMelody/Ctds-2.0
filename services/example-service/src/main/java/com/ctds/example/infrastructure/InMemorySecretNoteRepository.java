package com.ctds.example.infrastructure;

import com.ctds.example.domain.SecretNote;
import com.ctds.example.domain.SecretNoteRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 基础设施层：内存仓储（演示，同 GreetingRepository 模式）。
 */
@Repository
public class InMemorySecretNoteRepository implements SecretNoteRepository {

    private final Map<UUID, SecretNote> store = new ConcurrentHashMap<>();

    @Override
    public SecretNote save(final SecretNote note) {
        store.put(note.id(), note);
        return note;
    }

    @Override
    public Optional<SecretNote> findById(final UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public void replaceEnvelope(final UUID id, final String envelope) {
        store.put(id, new SecretNote(id, envelope));
    }
}
