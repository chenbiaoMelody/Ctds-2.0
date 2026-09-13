package com.ctds.subject.application;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证配置参数（WBS-3.1.3 hifi 配置项表；规格 §7 Q3：核验失败上限为业务可调参数，数值不锁死）。
 */
@ConfigurationProperties(prefix = "ctds.certification")
public class CertificationProperties {

    /** 当日法人核验失败次数上限（规格 §7 Q3 采 A：默认 5；超限当日锁定、次日自动恢复）。 */
    private int verifyDailyLimit = 5;

    /** 证照影像大小上限字节（lofi 待确认 5：默认 5MB）。 */
    private long uploadMaxBytes = 5 * 1024 * 1024;

    /** 证照影像格式白名单（lofi 待确认 5：jpg/jpeg/png）。 */
    private List<String> uploadAllowedExtensions = List.of("jpg", "jpeg", "png");

    /** SM4 密钥编号（L4 字段加密唯一入口 common-crypto 的 keyRef，密钥材料不入库）。 */
    private String materialKeyRef = "subject-cert-material";

    public int getVerifyDailyLimit() {
        return verifyDailyLimit;
    }

    public void setVerifyDailyLimit(final int value) {
        this.verifyDailyLimit = value;
    }

    public long getUploadMaxBytes() {
        return uploadMaxBytes;
    }

    public void setUploadMaxBytes(final long value) {
        this.uploadMaxBytes = value;
    }

    public List<String> getUploadAllowedExtensions() {
        return uploadAllowedExtensions;
    }

    public void setUploadAllowedExtensions(final List<String> value) {
        this.uploadAllowedExtensions = value;
    }

    public String getMaterialKeyRef() {
        return materialKeyRef;
    }

    public void setMaterialKeyRef(final String value) {
        this.materialKeyRef = value;
    }
}
