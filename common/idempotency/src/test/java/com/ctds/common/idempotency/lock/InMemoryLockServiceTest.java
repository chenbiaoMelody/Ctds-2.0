package com.ctds.common.idempotency.lock;

/**
 * 内存实现锁契约测试（hifi B8）：真实语义全量断言；演示/单测主路径。
 */
class InMemoryLockServiceTest extends LockServiceContractTest {

    @Override
    protected LockService lockService() {
        return new InMemoryLockService();
    }
}
