package com.ctds.connector.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.connector.application.ConnectorDetail;
import com.ctds.connector.application.ConnectorQueryService;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.interfaces.dto.ConnectorViews.ActionLogView;
import com.ctds.connector.interfaces.dto.ConnectorViews.BindingView;
import com.ctds.connector.interfaces.dto.ConnectorViews.ConnectorView;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已注册连接器读面与心跳（hifi §5 #7~#11）。
 * 详情 / 绑定查验实时读 DID 域（DID 不可用 → 503 fail-closed）；列表摘要不查 DID（避免 N 次跨域调用）。
 */
@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorController {

    private static final Pattern CONNECTOR_NO = Pattern.compile("^CN\\d{6}$");
    private static final String OPERATOR_ROLE = "admin";

    private final ConnectorQueryService queryService;

    public ConnectorController(final ConnectorQueryService queryService) {
        this.queryService = queryService;
    }

    /** 我的已注册连接器列表（分页；摘要不含 DID 状态）。 */
    @GetMapping(path = "/mine", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.read")
    public ApiResult<PageResult<ConnectorView>> myConnectors(
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageQuery page = PageQuery.of(pageNum, pageSize, null);
        final String subjectNo = AuthContext.subject();
        final List<ConnectorView> views = queryService.myConnectors(subjectNo, page).stream()
                .map(registration -> ConnectorView.summary(registration, queryService.online(registration)))
                .toList();
        return ApiResult.ok(PageResult.of(views, queryService.countMyConnectors(subjectNo), page));
    }

    /** 连接器详情（属主或运营方；含绑定与实时 DID 状态）。 */
    @GetMapping(path = "/{connectorNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.read")
    public ApiResult<ConnectorView> detail(@PathVariable final String connectorNo) {
        requireConnectorNo(connectorNo);
        return ApiResult.ok(ConnectorView.of(queryService.detail(AuthContext.subject(),
                isOperator(), connectorNo)));
    }

    /** 绑定查验（行为 8 验收 1~3：连接器 ↔ DID ↔ 建立时间 + 实时状态；查验动作留痕）。 */
    @GetMapping(path = "/{connectorNo}/binding", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.read")
    public ApiResult<BindingView> binding(@PathVariable final String connectorNo) {
        requireConnectorNo(connectorNo);
        final ConnectorDetail detail = queryService.binding(AuthContext.subject(), isOperator(),
                connectorNo);
        return ApiResult.ok(BindingView.of(detail.binding(), detail.didStatus()));
    }

    /** 动作留痕分页（属主或运营方；读取零新增留痕，防递归）。 */
    @GetMapping(path = "/{connectorNo}/action-logs", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.read")
    public ApiResult<PageResult<ActionLogView>> actionLogs(@PathVariable final String connectorNo,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        requireConnectorNo(connectorNo);
        final PageQuery page = PageQuery.of(pageNum, pageSize, null);
        final List<ActionLogView> views = queryService.actionLogs(AuthContext.subject(), isOperator(),
                connectorNo, page).stream().map(ActionLogView::of).toList();
        return ApiResult.ok(PageResult.of(views,
                queryService.countActionLogs(AuthContext.subject(), isOperator(), connectorNo), page));
    }

    /** 心跳上报（仅属主 + 仅已注册态；在线状态读时派生）。 */
    @PostMapping(path = "/{connectorNo}/heartbeats", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.heartbeat")
    public ApiResult<ConnectorView> heartbeat(@PathVariable final String connectorNo) {
        requireConnectorNo(connectorNo);
        return ApiResult.ok(ConnectorView.of(queryService.heartbeat(AuthContext.subject(), connectorNo)));
    }

    private static boolean isOperator() {
        return AuthContext.roles().contains(OPERATOR_ROLE);
    }

    private static void requireConnectorNo(final String connectorNo) {
        if (connectorNo == null || !CONNECTOR_NO.matcher(connectorNo).matches()) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
    }
}
