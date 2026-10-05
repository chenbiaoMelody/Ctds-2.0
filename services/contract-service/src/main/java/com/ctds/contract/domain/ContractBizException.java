package com.ctds.contract.domain;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCode;

/**
 * 合约业务异常：BizException 的合约域语义子类（错误码为 1008 段码表）。
 * 由 interfaces.ContractExceptionHandler 精确映射 HTTP（403/409/404/400/503）——common 全局处理器
 * 的默认映射（C/B→400、S→500）对合约码不满足 hifi §3 状态列，专用子类让精确处理器精准拦截，
 * common 组件零改动（沿 catalog CatalogBizException / space SpaceBizException 先例）。
 */
public class ContractBizException extends BizException {

    public ContractBizException(final ErrorCode errorCode, final String message) {
        super(errorCode, message);
    }
}
