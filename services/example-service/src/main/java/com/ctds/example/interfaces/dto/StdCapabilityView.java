package com.ctds.example.interfaces.dto;

/**
 * 标准能力域状态视图（WBS 2.4.8 演示端点出参）：code 为稳定域码、name 中文名、
 * implemented 是否已开放、message 业务可读说明。
 */
public record StdCapabilityView(String code, String name, boolean implemented, String message) {
}
