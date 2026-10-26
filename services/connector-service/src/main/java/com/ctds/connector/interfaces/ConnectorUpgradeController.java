package com.ctds.connector.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.connector.application.ConnectorUpgradeService;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.interfaces.dto.ConnectorLifecycleViews.UpgradeRequest;
import com.ctds.connector.interfaces.dto.ConnectorLifecycleViews.VersionChangeView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 升级与版本兼容端点（hifi §5 #1~#4）：属性主提交升级登记、运营方显式确认（不兼容升级）。
 * 身份一律取 {@link AuthContext}；运营方 = admin 角色（connector.upgrade.confirm 持有者）。
 */
@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorUpgradeController {

    private static final Pattern CONNECTOR_NO = Pattern.compile("^CN\\d{6}$");
    private static final Pattern UPGRADE_NO = Pattern.compile("^UC\\d{6}$");
    private static final String OPERATOR_ROLE = "admin";

    private final ConnectorUpgradeService upgradeService;

    public ConnectorUpgradeController(final ConnectorUpgradeService upgradeService) {
        this.upgradeService = upgradeService;
    }

    /** 升级登记（行为 9 验收 1~3）。 */
    @PostMapping(path = "/{connectorNo}/upgrades", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.upgrade")
    public ApiResult<VersionChangeView> request(@PathVariable final String connectorNo,
            @Valid @RequestBody final UpgradeRequest request) {
        requireConnectorNo(connectorNo);
        return ApiResult.ok(VersionChangeView.of(upgradeService.request(AuthContext.subject(),
                connectorNo, request.toVersion(), request.compatibility())));
    }

    /** 版本历史链（属主或运营方；时间倒序）。 */
    @GetMapping(path = "/{connectorNo}/upgrades", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.read")
    public ApiResult<PageResult<VersionChangeView>> history(@PathVariable final String connectorNo,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        requireConnectorNo(connectorNo);
        final PageQuery page = PageQuery.of(pageNum, pageSize, null);
        final List<VersionChangeView> views = upgradeService
                .history(AuthContext.subject(), isOperator(), connectorNo, page).stream()
                .map(VersionChangeView::of).toList();
        return ApiResult.ok(PageResult.of(views,
                upgradeService.countHistory(AuthContext.subject(), isOperator(), connectorNo), page));
    }

    /** 待确认队列（运营方；提交时间升序）。 */
    @GetMapping(path = "/upgrades/pending", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.upgrade.confirm")
    public ApiResult<PageResult<VersionChangeView>> pendingQueue(
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageQuery page = PageQuery.of(pageNum, pageSize, null);
        final List<VersionChangeView> views = upgradeService.pendingQueue(page).stream()
                .map(VersionChangeView::of).toList();
        return ApiResult.ok(PageResult.of(views, upgradeService.countPending(), page));
    }

    /** 不兼容升级显式确认（运营方；确认后版本替换生效）。 */
    @PostMapping(path = "/{connectorNo}/upgrades/{upgradeNo}/confirmation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.upgrade.confirm")
    public ApiResult<VersionChangeView> confirm(@PathVariable final String connectorNo,
            @PathVariable final String upgradeNo) {
        requireConnectorNo(connectorNo);
        if (upgradeNo == null || !UPGRADE_NO.matcher(upgradeNo).matches()) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
        return ApiResult.ok(VersionChangeView.of(
                upgradeService.confirm(AuthContext.subject(), connectorNo, upgradeNo)));
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
