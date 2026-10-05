package com.ctds.contract.application;

/**
 * 发起侧校验结果（hifi §2.4 QV1，供 3.4.3 合约发起流程在锁定版本快照前调用；
 * 本卡为应用层方法契约——合约域同宿主直调，无 HTTP 端点）。
 */
public record InitiationCheck(boolean valid, InitiationInvalidReason invalidReason) {

    /** 无效原因三态（hifi §2.4：不存在 / 版本不存在 / 已停用）。 */
    public enum InitiationInvalidReason {
        /** 模板不存在。 */
        TEMPLATE_NOT_FOUND,
        /** 指定版本不存在。 */
        VERSION_NOT_FOUND,
        /** 模板已停用（停用模板不可用于新发起——行为 1 规则 4；发起侧拒绝兑现归 3.4.3）。 */
        TEMPLATE_DISABLED
    }

    /** 有效态工厂（组件访问器 valid() 与工厂同名冲突，工厂命名 ok）。 */
    public static InitiationCheck ok() {
        return new InitiationCheck(true, null);
    }

    /** 无效态工厂。 */
    public static InitiationCheck invalid(final InitiationInvalidReason reason) {
        return new InitiationCheck(false, reason);
    }
}
