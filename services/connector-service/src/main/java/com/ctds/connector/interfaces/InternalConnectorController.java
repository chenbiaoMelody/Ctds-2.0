package com.ctds.connector.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.connector.application.ConnectorQueryService;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.interfaces.dto.ConnectorViews.ConnectorView;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部只读端点（hifi §5 #12）：供 3.6.4 生命周期 / 3.6.7 控制台 / 3.6.8 集成与 SDK 校验消费。
 * 功能门槛 = 服务身份专用权限点 connector.internal.read（本卡由 admin 直调验证，消费方角色随各卡追加）。
 *
 * <p>WBS-3.6.8 W9 / DB-68 路径统一：收敛为全仓内部面惯例 {@code /api/v1/{域}/internal/{资源}}
 * （消除重复域段 {@code internal/connectors}；首个平台消费方 3.6.8 交付链上线前统一，ADR-027 回写）。</p>
 */
@RestController
@RequestMapping("/api/v1/connectors/internal")
public class InternalConnectorController {

    private static final Pattern CONNECTOR_NO = Pattern.compile("^CN\\d{6}$");

    private final ConnectorQueryService queryService;

    public InternalConnectorController(final ConnectorQueryService queryService) {
        this.queryService = queryService;
    }

    /** 连接器 + 绑定 + 实时 DID 状态（不存在 / 未注册 → 同码同文）。 */
    @GetMapping(path = "/{connectorNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.internal.read")
    public ApiResult<ConnectorView> get(@PathVariable final String connectorNo) {
        if (connectorNo == null || !CONNECTOR_NO.matcher(connectorNo).matches()) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
        return ApiResult.ok(ConnectorView.of(queryService.internal(connectorNo)));
    }
}
