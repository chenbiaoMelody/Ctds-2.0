package com.ctds.example.infrastructure;

import com.ctds.example.domain.Greeting;
import com.ctds.example.domain.GreetingRepository;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Repository;

/**
 * 基础设施层：内存仓储实现（骨架演示用，正式持久化随 C-1.x 数据模型落地）。
 */
@Repository
public class InMemoryGreetingRepository implements GreetingRepository {

    private final List<Greeting> store = new CopyOnWriteArrayList<>();

    @Override
    public Greeting save(final Greeting greeting) {
        store.add(greeting);
        return greeting;
    }

    @Override
    public List<Greeting> findAll() {
        return List.copyOf(store);
    }
}
