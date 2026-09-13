package com.ctds.subject.application;

/**
 * 法人核验发起命令（规格行为 3 第 1 条；身份证号仅用于本次核验，L4 加密留痕、不回显）。
 */
public record VerificationCommand(String legalPersonName, String legalPersonIdNo) {
}
