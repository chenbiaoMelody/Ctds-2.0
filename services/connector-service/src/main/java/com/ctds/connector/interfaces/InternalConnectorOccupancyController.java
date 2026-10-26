package com.ctds.connector.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.connector.application.ConnectorOccupancyService;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.interfaces.dto.ConnectorLifecycleViews.OccupancyRequest;
import com.ctds.connector.interfaces.dto.ConnectorLifecycleViews.OccupancyView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在途作业占用内部面（hifi §5 #7~#9）：使用方（交付链 / SDK，接入归 3.6.8）登记与释放；
 * 登记与释放均幂等；仅服务身份角色持有内部写权限点。
 */
@RestController
@RequestMapping("/api/v1/connectors/internal")
public class InternalConnectorOccupancyController {

    private static final Pattern CONNECTOR_NO = Pattern.compile("^CN\\d{6}$");
    private static final int JOB_REF_MAX = 64;

    private final ConnectorOccupancyService occupancyService;

    public InternalConnectorOccupancyController(final ConnectorOccupancyService occupancyService) {
        this.occupancyService = occupancyService;
    }

    /** 登记在途作业（幂等）。 */
    @PostMapping(path = "/{connectorNo}/occupancies", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.internal.write")
    public ApiResult<OccupancyView> occupy(@PathVariable final String connectorNo,
            @Valid @RequestBody final OccupancyRequest request) {
        requireConnectorNo(connectorNo);
        return ApiResult.ok(OccupancyView.of(occupancyService.occupy(AuthContext.subject(), connectorNo,
                request.jobRef())));
    }

    /** 释放作业（幂等：已释放直接返回既有记录）。 */
    @DeleteMapping(path = "/{connectorNo}/occupancies/{jobRef}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.internal.write")
    public ApiResult<OccupancyView> release(@PathVariable final String connectorNo,
            @PathVariable final String jobRef) {
        requireConnectorNo(connectorNo);
        requireJobRef(jobRef);
        return ApiResult.ok(OccupancyView.of(
                occupancyService.release(AuthContext.subject(), connectorNo, jobRef)));
    }

    /** 占用清单（内部只读，供 3.6.8 演练与运维排查）。 */
    @GetMapping(path = "/{connectorNo}/occupancies", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.internal.read")
    public ApiResult<List<OccupancyView>> list(@PathVariable final String connectorNo) {
        requireConnectorNo(connectorNo);
        return ApiResult.ok(occupancyService.list(connectorNo).stream().map(OccupancyView::of).toList());
    }

    private static void requireConnectorNo(final String connectorNo) {
        if (connectorNo == null || !CONNECTOR_NO.matcher(connectorNo).matches()) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
    }

    private static void requireJobRef(final String jobRef) {
        if (jobRef == null || jobRef.isBlank() || jobRef.length() > JOB_REF_MAX) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
    }
}
