package com.ctds.std.did;

/**
 * 本空间 DID 状态读取端口（WBS-3.1.10 hifi §3 Q7=A 端口倒置）：本域定义端口、DID 服务提供实现
 * （进程内委托既有解析能力）。出向验证借此读取本空间状态——<b>无 HTTP 自调用、不新建权威源</b>。
 * <p>端口实现抛出异常或返回 null 时，视为通道取数异常。</p>
 */
@FunctionalInterface
public interface LocalDidStatusPort {

    LocalDidStatus statusOf(String did);
}
