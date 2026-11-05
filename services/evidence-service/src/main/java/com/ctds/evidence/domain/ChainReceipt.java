package com.ctds.evidence.domain;

/**
 * 上链回执：交易标识只由适配器产生（规格 B4-4：无手工填写入口）。
 */
public record ChainReceipt(String chainTxId, long blockNo) {
}
