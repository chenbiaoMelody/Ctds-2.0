package com.ctds.example.domain;

import java.util.List;
import java.util.UUID;

/**
 * 领域仓储接口：实现在基础设施层（依赖倒置）。
 */
public interface GreetingRepository {

    Greeting save(Greeting greeting);

    List<Greeting> findAll();

    /** 按标识删除；返回是否实际删除（false = 不存在）。 */
    boolean deleteById(UUID id);
}
