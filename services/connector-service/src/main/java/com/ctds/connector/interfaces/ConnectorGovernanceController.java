package com.ctds.connector.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.connector.application.ConnectorQueryService;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorStatus;
import com.ctds.connector.interfaces.dto.ConnectorViews.ConnectorView;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营方全平台连接器列表（WBS-3.6.7 Q3-A 加性只读端点，承接 C-6.2 S1-6 与运营方健康总览）：
 * 零新表 / 零新码 / 零迁移 / 零新权限点；复用既有仓储与列表摘要口径（online 读时派生）。
 * status 按字符串手工解析——非法值映射 400 可读业务码，不把枚举直接绑定到参数（避免类型转换落入兜底 500）。
 */
@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorGovernanceController {

    private final ConnectorQueryService queryService;

    public ConnectorGovernanceController(final ConnectorQueryService queryService) {
        this.queryService = queryService;
    }

    /** 全平台连接器分页（运营方；status 空 = 三态全部；created_at DESC, id DESC）。 */
    @GetMapping(path = "/governance/connectors", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.review")
    public ApiResult<PageResult<ConnectorView>> governanceConnectors(
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final ConnectorStatus parsed = parseStatus(status);
        final PageQuery page = PageQuery.of(pageNum, pageSize, null);
        final List<ConnectorView> views = queryService.governanceConnectors(parsed, page).stream()
                .map(registration -> ConnectorView.summary(registration,
                        queryService.online(registration)))
                .toList();
        return ApiResult.ok(PageResult.of(views,
                queryService.countGovernanceConnectors(parsed), page));
    }

    private static ConnectorStatus parseStatus(final String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ConnectorStatus.of(status.trim());
        } catch (final IllegalArgumentException ex) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
    }
}
