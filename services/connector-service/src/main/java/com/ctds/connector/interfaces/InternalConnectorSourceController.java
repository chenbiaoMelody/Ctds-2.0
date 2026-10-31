package com.ctds.connector.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.connector.application.ConnectorSourceReadService;
import com.ctds.connector.application.ConnectorSourceReadService.ReadOutcome;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.interfaces.dto.ConnectorSourceViews.ReadRequest;
import com.ctds.connector.interfaces.dto.ConnectorSourceViews.SourceReadView;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 源数据读取内部面（hifi §5 #6）：<b>仅使用控制判定放行后由交付链调用</b>（判定次序归 3.6.8）。
 *
 * <p>服务身份（{@code connector.internal.read}）；本地前置 = 连接器已注册 + 源归属 + 参数合法 + 类型有适配器；
 * 前置拒绝时适配器零调用。返回摘要不含数据本体；留痕五要素（操作者 / 连接器 / 源 / 合约 / 结果）。</p>
 */
@RestController
@RequestMapping("/api/v1/connectors/internal")
public class InternalConnectorSourceController {

    private static final Pattern CONNECTOR_NO = Pattern.compile("^CN\\d{6}$");
    private static final Pattern SOURCE_NO = Pattern.compile("^SR\\d{6}$");

    private final ConnectorSourceReadService readService;

    public InternalConnectorSourceController(final ConnectorSourceReadService readService) {
        this.readService = readService;
    }

    /** 读取源数据（判定放行后调用；成功 / 失败均为 200 结论，摘要不含本体）。 */
    @PostMapping(path = "/{connectorNo}/sources/{sourceNo}/reads",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.internal.read")
    public ApiResult<SourceReadView> read(@PathVariable final String connectorNo,
            @PathVariable final String sourceNo, @Valid @RequestBody final ReadRequest request) {
        requireConnectorNo(connectorNo);
        requireSourceNo(sourceNo);
        final ReadOutcome outcome = readService.read(AuthContext.subject(), connectorNo, sourceNo,
                request.jobRef(), request.contractNo(), request.purpose());
        return ApiResult.ok(SourceReadView.of(sourceNo, connectorNo, request.contractNo(),
                outcome.result().success(), outcome.result().summary(), outcome.readAt()));
    }

    private static void requireConnectorNo(final String connectorNo) {
        if (connectorNo == null || !CONNECTOR_NO.matcher(connectorNo).matches()) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
    }

    private static void requireSourceNo(final String sourceNo) {
        if (sourceNo == null || !SOURCE_NO.matcher(sourceNo).matches()) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
    }
}
