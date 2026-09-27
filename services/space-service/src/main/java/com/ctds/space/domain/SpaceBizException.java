package com.ctds.space.domain;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCode;

/**
 * 空间业务异常：BizException 的空间语义子类（错误码仍为 1006 段码表）。
 * 由 interfaces.SpaceExceptionHandler 精确映射 HTTP（403/409/404/400/503）——common 全局处理器
 * 的默认映射（C/B→400、S→500）对空间码不满足 hifi §2 状态列，专用子类让精确处理器精准拦截，
 * common 组件零改动（沿 subject CertificationExceptionHandler 先例）。
 */
public class SpaceBizException extends BizException {

    public SpaceBizException(final ErrorCode errorCode, final String message) {
        super(errorCode, message);
    }
}
