package com.ctds.connector.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 连接器服务码表（模块位 10，1010 段；占用留痕 ADR-027 §2，沿 ADR-015 §3 顺延制度）。
 * 对外文案为服务端常量，禁止拼接用户输入（章程 4.3：不暴露内部实现）。
 * <p>不设 B 型码：核验拒绝是 200 业务结论（WBS-3.6.3 Q8-A），不是接口错误。</p>
 */
public final class ConnectorErrorCodes {

    /** 参数不合法：要素缺失 / 格式非法 / 编号格式非法 / 路径变量越界。→ 400 */
    public static final ErrorCode PARAM_INVALID = ErrorCode.of("1010C0001");
    /** 同主体同名连接器活跃档案已存在（防重）。→ 400 */
    public static final ErrorCode NAME_DUPLICATED = ErrorCode.of("1010C0002");
    /** 未找到该记录或无权访问（防枚举同码同文：非属主 / 不存在一律同码）。→ 400 */
    public static final ErrorCode NOT_FOUND_OR_DENIED = ErrorCode.of("1010C0003");
    /** 当前状态不允许该操作（补正 / 核验 / 心跳的状态门槛）。→ 400 */
    public static final ErrorCode STATE_NOT_ALLOWED = ErrorCode.of("1010C0004");
    /** 核验第三查不通过：申请信息不完整。→ 核验结论（200） */
    public static final ErrorCode INFO_INCOMPLETE = ErrorCode.of("1010C0005");
    /** 核验第一查不通过 / 申请前置：主体资格不满足（防枚举同文）。→ 400 或核验结论 */
    public static final ErrorCode SUBJECT_NOT_ADMITTED = ErrorCode.of("1010C0006");
    /** 核验第二查不通过：主体 DID 非 ACTIVE（REVOKED / PENDING_ISSUE / NONE）。→ 核验结论（200） */
    public static final ErrorCode DID_NOT_VALID = ErrorCode.of("1010C0007");
    /** 主体服务不可用（不可达 / 超时 / 非 200 / 不可解析）→ 503 fail-closed。 */
    public static final ErrorCode SUBJECT_UNAVAILABLE = ErrorCode.of("1010S0001");
    /** DID 服务不可用（同上）→ 503 fail-closed。 */
    public static final ErrorCode DID_UNAVAILABLE = ErrorCode.of("1010S0002");

    public static final String PARAM_INVALID_MESSAGE = "请求参数不合法";
    public static final String NAME_DUPLICATED_MESSAGE = "同主体同名连接器已存在，请更换名称或走升级流程";
    public static final String NOT_FOUND_OR_DENIED_MESSAGE = "未找到该记录或无权访问";
    public static final String STATE_NOT_ALLOWED_MESSAGE = "当前状态不允许该操作";
    public static final String INFO_INCOMPLETE_MESSAGE = "申请信息不完整，请补正后重新提交核验";
    public static final String SUBJECT_NOT_ADMITTED_MESSAGE = "主体未入驻或状态不可接入连接器";
    public static final String DID_NOT_VALID_MESSAGE = "所属主体 DID 无效或已失效";
    public static final String SUBJECT_UNAVAILABLE_MESSAGE = "主体服务暂不可用，请稍后重试";
    public static final String DID_UNAVAILABLE_MESSAGE = "DID 服务暂不可用，请稍后重试";

    /** 核验拒绝理由（DID 三态分别可读，服务端常量）。 */
    public static final String DID_REVOKED_REASON = "所属主体 DID 已吊销";
    public static final String DID_PENDING_REASON = "所属主体 DID 尚未生效";
    public static final String DID_NONE_REASON = "所属主体尚未签发 DID";

    private ConnectorErrorCodes() {
    }
}
