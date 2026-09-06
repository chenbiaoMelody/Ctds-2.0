package com.ctds.example.application;

import com.ctds.example.domain.Greeting;
import com.ctds.example.domain.GreetingRepository;
import org.springframework.stereotype.Service;

/**
 * 应用服务：编排领域对象与仓储，禁止依赖接口层（章程 4.3）。
 */
@Service
public class GreetingService {

    private final GreetingRepository greetingRepository;

    public GreetingService(final GreetingRepository greetingRepository) {
        this.greetingRepository = greetingRepository;
    }

    public Greeting greet(final String message) {
        return greetingRepository.save(Greeting.of(message));
    }
}
