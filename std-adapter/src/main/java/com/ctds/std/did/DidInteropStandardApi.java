package com.ctds.std.did;

import com.ctds.std.StdDomainApi;

/**
 * 跨空间身份互认域契约（WBS-3.1.10 冻结"业务口径方法"：来访验证 / 出向验证 / 样例清单；ADR-008 变更补记登记）。
 * <p><b>诚实边界</b>：本域方法为平台内部业务口径，<b>不是信通院协议签名</b>——规范正式文本取得后按变更流程冻结
 * 协议签名并替换/扩展本域方法（ADR-008 特别说明）。</p>
 * <p>收口纪律（ADR-008 §3.1）：一切与外部标准打交道的互认协议逻辑只允许落在本模块；业务服务不得自行对接。</p>
 */
public interface DidInteropStandardApi extends StdDomainApi {

    /** 来访验证（对端主体凭对端 DID 来本空间办事）：三查（签名 → 对端状态 → 对端绑定）后给出结论。 */
    InteropVerification verifyInbound(InteropClaim claim);

    /** 出向验证（本空间主体去对端办事）：对端读取本空间 DID 状态并回放结论（双向口径）。 */
    InteropVerification verifyOutbound(String did);

    /** 预置样例清单（演示期模拟对端；规格 C-1.2 §7 Q3=A，剧本附录 A 组 E1 取用口径）。 */
    DidInteropSamples samples();
}
