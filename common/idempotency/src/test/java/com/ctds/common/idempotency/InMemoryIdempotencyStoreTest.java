package com.ctds.common.idempotency;

/**
 * 内存实现契约测试（hifi B8）：真实语义全量断言；演示/单测主路径。
 */
class InMemoryIdempotencyStoreTest extends IdempotencyStoreContractTest {

    @Override
    protected IdempotencyStore store() {
        return new InMemoryIdempotencyStore();
    }
}
