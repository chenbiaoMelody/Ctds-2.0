package com.ctds.contract.application;

/**
 * 修订模板命令（hifi §2.1 W2；frameworkHash = 条款框架稳定哈希，由 DTO 映射时经
 * {@code ClauseFramework.stableHash} 计算——幂等键成分：同框架内容重放返回首次新版本、
 * 不产生额外版本行；不同内容各出新版本）。
 */
public record ReviseTemplateCommand(
        String operatorNo,
        String templateNo,
        String clauseFrameworkJson,
        String frameworkHash) {
}
