package com.ctds.std.certification;

import com.ctds.std.StdDomain;
import com.ctds.std.StdDomainStatus;
import com.ctds.std.StdAdapterErrorCodes;
import java.util.UUID;

/**
 * 模拟认证渠道（WBS-3.1.3，规格行为 7 第 2 条 Q1 裁决口径：演示期零外部采购，按预置规则返回结果，
 * 支撑验收剧本 S1/S2 可重复执行；预置规则与剧本附录 A 严格对应）。
 * 无状态可共享多线程；后续接入真实渠道时由使用方替换 Bean 注册，业务代码零改动（ADR-008 第 4 条）。
 */
public class MockCertificationChannel implements CertificationStandardApi {

    /** 渠道标识（留痕要素，业务侧原样落库）。 */
    public static final String CHANNEL_CODE = "mock-certification";

    /** 预置模拟影像文件名标记（剧本附录 A 组 A1）。 */
    static final String PRESET_IMAGE_MARKER = "A1";

    /** 预置核验不通过规则：身份证号尾号为 8（剧本附录 A 组 A2）。 */
    static final String FAIL_ID_TAIL = "8";

    private final boolean channelError;

    public MockCertificationChannel(final boolean channelError) {
        this.channelError = channelError;
    }

    @Override
    public StdDomain domain() {
        return StdDomain.CERTIFICATION;
    }

    @Override
    public StdDomainStatus status() {
        return new StdDomainStatus(StdDomain.CERTIFICATION, true,
                "模拟认证渠道已开放（演示期预置规则口径，规格 C-1.1 行为 7）；真实渠道接入后本实现由使用方替换");
    }

    @Override
    public String channelCode() {
        return CHANNEL_CODE;
    }

    @Override
    public OcrRecognition ocrBusinessLicense(final byte[] image, final String fileName) {
        requireChannelAvailable();
        if (image == null || image.length == 0 || fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("image and fileName are required");
        }
        if (!fileName.contains(PRESET_IMAGE_MARKER)) {
            return OcrRecognition.unrecognizable(nextRequestNo(), "无法识别请重传（模拟渠道：非预置影像）");
        }
        return new OcrRecognition(true, "蓝天数据科技有限公司", "91330100MA27XW123X", "张伟",
                "杭州市XX区XX路88号", nextRequestNo(), "识别成功（模拟渠道预置影像 A1）");
    }

    @Override
    public LegalPersonVerification verifyLegalPerson(final String legalPersonName, final String legalPersonIdNo) {
        requireChannelAvailable();
        if (legalPersonName == null || legalPersonName.isBlank()
                || legalPersonIdNo == null || legalPersonIdNo.isBlank()) {
            throw new IllegalArgumentException("legalPersonName and legalPersonIdNo are required");
        }
        if (legalPersonIdNo.endsWith(FAIL_ID_TAIL)) {
            return new LegalPersonVerification(false, nextRequestNo(), "身份要素不匹配");
        }
        return new LegalPersonVerification(true, nextRequestNo(), null);
    }

    /** 异常注入开关（演示/测试 B8 fail-fast 口径）：开启后任何调用抛渠道技术异常。 */
    private void requireChannelAvailable() {
        if (channelError) {
            throw StdAdapterErrorCodes.channelUnavailable();
        }
    }

    private String nextRequestNo() {
        return "MOCK-" + UUID.randomUUID();
    }
}
