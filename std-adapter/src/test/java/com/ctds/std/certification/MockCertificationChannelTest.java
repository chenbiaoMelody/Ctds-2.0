package com.ctds.std.certification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import com.ctds.std.StdAdapterErrorCodes;
import com.ctds.std.StdDomain;
import org.junit.jupiter.api.Test;

/**
 * 模拟认证渠道预置规则单测（WBS-3.1.3 hifi B1；规则与验收剧本附录 A 严格对应，
 * 保证剧本 S1/S2 可重复执行——规格行为 7 验收标准第 1 条）。
 */
class MockCertificationChannelTest {

    private final MockCertificationChannel channel = new MockCertificationChannel(false);

    @Test
    void 域探活返回实名认证且已开放() {
        assertThat(channel.domain()).isEqualTo(StdDomain.CERTIFICATION);
        assertThat(channel.status().implemented()).isTrue();
        assertThat(channel.status().message()).isNotBlank();
    }

    @Test
    void 预置影像A1识别成功且要素固定() {
        final OcrRecognition result = channel.ocrBusinessLicense(new byte[]{1}, "营业执照A1.jpg");

        assertThat(result.recognizable()).isTrue();
        assertThat(result.subjectName()).isEqualTo("蓝天数据科技有限公司");
        assertThat(result.uscc()).isEqualTo("91330100MA27XW123X");
        assertThat(result.legalPerson()).isEqualTo("张伟");
        assertThat(result.regAddress()).isEqualTo("杭州市XX区XX路88号");
    }

    @Test
    void 非预置影像返回不可识别且不产生部分要素() {
        final OcrRecognition result = channel.ocrBusinessLicense(new byte[]{1}, "其他影像.png");

        assertThat(result.recognizable()).isFalse();
        assertThat(result.subjectName()).isNull();
        assertThat(result.uscc()).isNull();
    }

    @Test
    void 身份证尾号8核验不通过其余通过且流水号可留痕() {
        final LegalPersonVerification fail = channel.verifyLegalPerson("王芳", "330102199001018");
        final LegalPersonVerification pass = channel.verifyLegalPerson("张伟", "330102199001019");

        assertThat(fail.passed()).isFalse();
        assertThat(fail.failReason()).isNotBlank();
        assertThat(fail.channelRequestNo()).startsWith("MOCK-");
        assertThat(pass.passed()).isTrue();
        assertThat(pass.failReason()).isNull();
        assertThat(pass.channelRequestNo()).startsWith("MOCK-");
    }

    @Test
    void 异常注入开关触发渠道技术异常1003S0001() {
        final MockCertificationChannel broken = new MockCertificationChannel(true);

        assertThatThrownBy(() -> broken.ocrBusinessLicense(new byte[]{1}, "A1.jpg"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StdAdapterErrorCodes.CHANNEL_UNAVAILABLE));
        assertThatThrownBy(() -> broken.verifyLegalPerson("张伟", "330102199001019"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StdAdapterErrorCodes.CHANNEL_UNAVAILABLE));
    }

    @Test
    void 空入参快速失败属编程错误而非渠道异常() {
        assertThatThrownBy(() -> channel.ocrBusinessLicense(null, "A1.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.verifyLegalPerson(" ", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 政务CA有效证书A3验证通过且单位要素固定() {
        final GovCaVerification result = channel.verifyGovCaCertificate(new byte[]{1}, "政务证书A3.cer");

        assertThat(result.passed()).isTrue();
        assertThat(result.failReason()).isNull();
        assertThat(result.unitName()).isEqualTo("市大数据管理局");
        assertThat(result.channelRequestNo()).startsWith("MOCK-");
    }

    @Test
    void 政务CA过期证书A4拒绝并附明确原因() {
        final GovCaVerification result = channel.verifyGovCaCertificate(new byte[]{1}, "政务证书A4.cer");

        assertThat(result.passed()).isFalse();
        assertThat(result.failReason()).contains("过期");
        assertThat(result.channelRequestNo()).startsWith("MOCK-");
    }

    @Test
    void 政务CA非预置证书拒绝且不产生部分要素() {
        final GovCaVerification result = channel.verifyGovCaCertificate(new byte[]{1}, "其他证书.pem");

        assertThat(result.passed()).isFalse();
        assertThat(result.failReason()).isNotBlank();
        assertThat(result.unitName()).isNull();
    }

    @Test
    void 政务CA异常注入与空入参口径与既有方法一致() {
        final MockCertificationChannel broken = new MockCertificationChannel(true);

        assertThatThrownBy(() -> broken.verifyGovCaCertificate(new byte[]{1}, "A3.cer"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StdAdapterErrorCodes.CHANNEL_UNAVAILABLE));
        assertThatThrownBy(() -> channel.verifyGovCaCertificate(null, "A3.cer"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
