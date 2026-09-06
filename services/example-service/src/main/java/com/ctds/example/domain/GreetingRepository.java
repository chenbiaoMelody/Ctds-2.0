package com.ctds.example.domain;

/**
 * 领域仓储接口：实现在基础设施层（依赖倒置）。
 */
public interface GreetingRepository {

    Greeting save(Greeting greeting);
}
