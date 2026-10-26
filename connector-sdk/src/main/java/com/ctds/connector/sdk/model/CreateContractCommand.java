package com.ctds.connector.sdk.model;

/** 合约磋商创建命令（对齐平台合约域磋商要素——产品 / 用途 / 域）。 */
public record CreateContractCommand(Long productId, String purpose, String territory) {
}
