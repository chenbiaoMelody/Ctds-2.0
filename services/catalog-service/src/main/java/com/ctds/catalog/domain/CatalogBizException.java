package com.ctds.catalog.domain;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCode;

/**
 * 目录与资源业务异常：BizException 的目录域语义子类（错误码仍为 1007 段码表）。
 * 由 interfaces.CatalogExceptionHandler 精确映射 HTTP（403/409/404/400/503）——common 全局处理器
 * 的默认映射（C/B→400、S→500）对目录码不满足 hifi §2 状态列，专用子类让精确处理器精准拦截，
 * common 组件零改动（沿 space SpaceBizException 先例）。
 */
public class CatalogBizException extends BizException {

    public CatalogBizException(final ErrorCode errorCode, final String message) {
        super(errorCode, message);
    }
}
