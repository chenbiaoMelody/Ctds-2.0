package com.ctds.evidence.domain;

/**
 * 审计调阅 / 导出动作留痕仓储端口（WBS-3.8.4 hifi §2 / §4.3）：append-only——
 * 仅 insert（与按人按 kind 的只读查询），无任何更新 / 删除入口（T21 源码断言机器可证）。
 */
public interface AccessLogRepository {

    /** 只插（独立短事务由调用方承载；留痕失败上抛 = 调阅不返回，hifi §4.1）。 */
    void insert(AccessLogEntry entry);
}
