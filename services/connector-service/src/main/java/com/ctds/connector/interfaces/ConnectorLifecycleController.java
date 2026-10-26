package com.ctds.connector.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.connector.application.ConnectorCancellationService;
import com.ctds.connector.application.ConnectorLifecycleQueryService;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.interfaces.dto.ConnectorLifecycleViews.LifecycleView;
import com.ctds.connector.interfaces.dto.ConnectorViews.RegistrationView;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注销与生命周期聚合读（hifi §5 #5 / #6 / #10）：注销终态不可逆、绑定解除记录保留、
 * 已注销后历史读面保留（详情 / 留痕由 3.6.3 既有端点承载，零改动）。
 */
@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorLifecycleController {

    private static final Pattern CONNECTOR_NO = Pattern.compile("^CN\\d{6}$");
    private static final Pattern APPLICATION_NO = Pattern.compile("^CA\\d{6}$");
    private static final String OPERATOR_ROLE = "admin";

    private final ConnectorCancellationService cancellationService;
    private final ConnectorLifecycleQueryService lifecycleQueryService;

    public ConnectorLifecycleController(final ConnectorCancellationService cancellationService,
            final ConnectorLifecycleQueryService lifecycleQueryService) {
        this.cancellationService = cancellationService;
        this.lifecycleQueryService = lifecycleQueryService;
    }

    /** 注销已注册连接器（行为 10 验收 1~2）。 */
    @PostMapping(path = "/{connectorNo}/cancellation", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.cancel")
    public ApiResult<LifecycleView> cancel(@PathVariable final String connectorNo) {
        requireConnectorNo(connectorNo);
        final String callerNo = AuthContext.subject();
        final boolean operator = isOperator();
        cancellationService.cancel(callerNo, operator, connectorNo);
        return ApiResult.ok(LifecycleView.of(
                lifecycleQueryService.snapshot(callerNo, operator, connectorNo)));
    }

    /** 生命周期聚合读（含版本链 / 绑定状态 / 在途占用 / 注销时间；注销后仍可读）。 */
    @GetMapping(path = "/{connectorNo}/lifecycle", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.read")
    public ApiResult<LifecycleView> lifecycle(@PathVariable final String connectorNo) {
        requireConnectorNo(connectorNo);
        return ApiResult.ok(LifecycleView.of(lifecycleQueryService.snapshot(
                AuthContext.subject(), isOperator(), connectorNo)));
    }

    /** 撤回待核验申请（待核验档案无连接器编号，按申请单号寻址；行为 10 的撤回语义）。 */
    @DeleteMapping(path = "/registrations/{applicationNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.cancel")
    public ApiResult<RegistrationView> withdraw(@PathVariable final String applicationNo) {
        if (applicationNo == null || !APPLICATION_NO.matcher(applicationNo).matches()) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
        return ApiResult.ok(RegistrationView.of(
                cancellationService.withdraw(AuthContext.subject(), applicationNo)));
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
