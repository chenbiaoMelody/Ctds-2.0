package com.ctds.subject.application;

/** 注册命令（应用层入参；interfaces 层负责格式校验，应用层做业务规则校验）。 */
public record RegisterCommand(
        String subjectName,
        String uscc,
        String subjectType,
        String regAddress,
        String contactName,
        String contactPhone,
        String adminAccount) {
}
