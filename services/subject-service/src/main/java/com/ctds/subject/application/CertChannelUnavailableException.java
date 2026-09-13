package com.ctds.subject.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.subject.domain.SubjectErrorCodes;

/**
 * 认证渠道不可用异常（规格行为 3 第 5 条 fail-fast：明确业务错误、不静默降级、不留半完成状态）。
 * 专类出站：经本地处理器精确映射 503 并保留业务文案（全局 S 型脱敏不影响本码）。
 */
public class CertChannelUnavailableException extends BizException {

    public CertChannelUnavailableException() {
        super(SubjectErrorCodes.CERT_CHANNEL_UNAVAILABLE, SubjectErrorCodes.CERT_CHANNEL_UNAVAILABLE_MESSAGE);
    }
}
