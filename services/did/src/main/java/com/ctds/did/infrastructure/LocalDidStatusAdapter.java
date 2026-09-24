package com.ctds.did.infrastructure;

import com.ctds.common.errorcode.BizException;
import com.ctds.did.application.DidResolutionService;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.DidStatus;
import com.ctds.std.did.LocalDidStatus;
import com.ctds.std.did.LocalDidStatusPort;

/**
 * 本空间 DID 状态端口实现（WBS-3.1.10 hifi §3 Q7=A 端口倒置）：<b>进程内委托</b> 3.1.9 既有解析能力，
 * 不新增 HTTP 自调用、不复制状态到互认域。
 * <p>未登记 → {@code registered=false}（对端回放为"未登记"）；其余解析异常原样上抛，由互认域按通道取数异常处理
 * （UNAVAILABLE，不冒充"不通过"）。</p>
 */
public class LocalDidStatusAdapter implements LocalDidStatusPort {

    private final DidResolutionService resolutionService;

    public LocalDidStatusAdapter(final DidResolutionService resolutionService) {
        this.resolutionService = resolutionService;
    }

    @Override
    public LocalDidStatus statusOf(final String did) {
        try {
            return new LocalDidStatus(true, resolutionService.resolve(did).status() == DidStatus.ACTIVE);
        } catch (final BizException e) {
            if (DidErrorCodes.DID_NOT_REGISTERED.equals(e.getErrorCode())) {
                return new LocalDidStatus(false, false);
            }
            throw e;
        }
    }
}
