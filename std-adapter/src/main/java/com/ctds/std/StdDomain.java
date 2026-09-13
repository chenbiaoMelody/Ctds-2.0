package com.ctds.std;

/**
 * 标准能力域清单（WBS 2.4.8；域划分依据 = WBS §1.7 强依赖清单"std-adapter 骨架 → DID 互认、互联互通、测评证据"）。
 * 域清单本身即契约（ADR-008）：新增域 = ADR-008 变更 + 新任务卡，禁止就地加枚举值。
 */
public enum StdDomain {

    /** 互联互通：区域枢纽对接、目录互挂、产品互挂（PRD C-9.1 / C-9.2，协议接口随 WBS 4.x 冻结）。 */
    INTERCONNECT("interconnect", "互联互通"),

    /** DID 互认：政务 CA 接入、跨空间身份互认、智能体互认（PRD C-9.3，收口 WBS 3.1.4 / 3.1.10）。 */
    DID_INTEROP("did", "跨空间身份互认"),

    /** 测评证据：信通院测评证据采集与互联互通契约测试自动轨（章程 G1 门禁，M2 起建设）。 */
    EVIDENCE("evidence", "测评证据"),

    /**
     * 实名认证：营业执照 OCR、法人核验、政务 CA 证书验证（PRD C-1.1，规格 C-1.1 行为 7 收口本模块；
     * OCR 与法人核验协议方法随 WBS-3.1.3 冻结，政务 CA 方法随 WBS-3.1.4 同域扩展——ADR-008 补记 2026-09-13）。
     */
    CERTIFICATION("certification", "实名认证");

    private final String code;
    private final String displayName;

    StdDomain(final String code, final String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** 进入 API 响应的稳定域码（变更 = 契约破坏，须走 ADR-008）。 */
    public String code() {
        return code;
    }

    /** 中文域名（业务可读展示用）。 */
    public String displayName() {
        return displayName;
    }
}
