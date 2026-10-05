package com.ctds.contract.domain;

import java.time.LocalDateTime;

/**
 * 模板操作留痕（一行 = 一条留痕；四要素：谁/何时/哪个模板+版本/什么动作——行为 1 规则 7）。
 * reasonCode 为拒绝理由码（1008 码位尾号，如 C0002——DB-31 口径直接存码位尾号）；
 * fromValue/toValue 承载修订（旧/新版本号）与启停（旧/新状态）的 from→to；
 * 只插不改（无更新路径），不含敏感原文与条款框架全文（全文在版本表按版本号可查）。
 */
public record TemplateActionLog(
        Long id,
        String templateNo,
        Integer versionNo,
        TemplateAction action,
        String actorSubjectNo,
        String reasonCode,
        String fromValue,
        String toValue,
        LocalDateTime createdAt) {
}
