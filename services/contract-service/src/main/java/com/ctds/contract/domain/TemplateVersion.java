package com.ctds.contract.domain;

import java.time.LocalDateTime;

/**
 * 合约模板版本（一行 = 一个已发布版本；行级版本化——修订永远新增行，<b>版本行不可变</b>）。
 * 版本行即"版本快照"载体（行为 1 规则 3）：旧版本保留可查由行保留天然承载；
 * 合约发起时锁定快照的写入动作归 3.4.3（本卡交付 QV1/QV2 校验与读取）。
 * clauseFrameworkJson 为条款框架 JSON 载体（槽位集合实例化，结构见 {@link ClauseFramework}）。
 */
public record TemplateVersion(
        Long id,
        long templateId,
        int versionNo,
        String clauseFrameworkJson,
        String publishedBy,
        LocalDateTime publishedAt) {
}
