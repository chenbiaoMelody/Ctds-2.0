package com.ctds.std.certification;

import com.ctds.std.StdDomain;
import com.ctds.std.StdDomainApi;
import com.ctds.std.StdDomainStatus;

/**
 * 实名认证域标准接口（WBS-3.1.3，规格 C-1.1 行为 7：三类外部认证能力统一抽象、实现收口本模块，
 * 业务代码只见接口不见具体渠道；ADR-008 补记 2026-09-13）。
 * 本包冻结 OCR 识别与法人核验两个协议方法；政务 CA 证书验证方法随 WBS-3.1.4 同域扩展。
 */
public interface CertificationStandardApi extends StdDomainApi {

    /** 本接口所属能力域。 */
    @Override
    StdDomain domain();

    /** 域当前状态（是否开放 + 业务可读说明）。 */
    @Override
    StdDomainStatus status();

    /** 渠道标识（规格行为 7 第 3 条留痕要素；业务侧原样落库，不得硬编码具体渠道名）。 */
    String channelCode();

    /**
     * 营业执照 OCR 识别（规格行为 2）：识别证照要素供业务回填核对。
     * 不可识别返回 recognizable=false（业务提示重传），不抛业务异常；
     * 渠道技术异常（不可用/超时）抛 {@link com.ctds.std.StdAdapterErrorCodes#CHANNEL_UNAVAILABLE}，
     * 由调用方转译 fail-fast（规格行为 3 第 5 条 / 行为 7 第 4 条口径）。
     *
     * @param image    影像字节（必填非空）
     * @param fileName 影像文件名（必填；模拟渠道以文件名匹配预置规则）
     */
    OcrRecognition ocrBusinessLicense(byte[] image, String fileName);

    /**
     * 法人实人核验（规格行为 3）：由渠道返回"通过/不通过"结论。
     * 业务不通过返回 passed=false + 流水号（计入核验失败次数）；
     * 渠道技术异常抛 {@link com.ctds.std.StdAdapterErrorCodes#CHANNEL_UNAVAILABLE}（不计入失败次数）。
     *
     * @param legalPersonName 法人姓名（必填）
     * @param legalPersonIdNo 法人身份证号（必填，仅用于本次核验）
     */
    LegalPersonVerification verifyLegalPerson(String legalPersonName, String legalPersonIdNo);
}
