package com.ctds.metering.application;

/** 单源采集计数（scanned = 本次扫描行数；collected = 本次新增行数，重复触发 = 0 幂等）。 */
public record ScannedCollected(long scanned, long collected) {
}
