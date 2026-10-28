package com.ctds.connector.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.connector.application.ConnectorSourceService;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorSource;
import com.ctds.connector.interfaces.dto.ConnectorSourceViews.AmendSourceRequest;
import com.ctds.connector.interfaces.dto.ConnectorSourceViews.RegisterSourceRequest;
import com.ctds.connector.interfaces.dto.ConnectorSourceViews.SourceCheckView;
import com.ctds.connector.interfaces.dto.ConnectorSourceViews.SourceView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 源接入端点（hifi §5 #1~#5）：登记 / 变更 / 清单 / 详情 / 自检。
 *
 * <p>属主动作（登记 / 变更 / 自检）持 {@code connector.source.manage}；读面复用 {@code connector.read}
 * （运营方可读）。身份一律取 {@link AuthContext}；响应只有掩码，凭据明文不回显。</p>
 */
@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorSourceController {

    private static final Pattern CONNECTOR_NO = Pattern.compile("^CN\\d{6}$");
    private static final Pattern SOURCE_NO = Pattern.compile("^SR\\d{6}$");
    private static final String OPERATOR_ROLE = "admin";

    private final ConnectorSourceService sourceService;

    public ConnectorSourceController(final ConnectorSourceService sourceService) {
        this.sourceService = sourceService;
    }

    /** 源接入登记（四类源均受理；登记即签发 SR+6 位）。 */
    @PostMapping(path = "/{connectorNo}/sources", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.source.manage")
    public ApiResult<SourceView> register(@PathVariable final String connectorNo,
            @Valid @RequestBody final RegisterSourceRequest request) {
        requireConnectorNo(connectorNo);
        final ConnectorSource source = sourceService.register(AuthContext.subject(), connectorNo,
                request.sourceType(), request.endpoint(), request.credential());
        return ApiResult.ok(SourceView.summary(source));
    }

    /** 变更源地址 / 凭据（旧 / 新要素入留痕；凭据仍只显掩码）。 */
    @PutMapping(path = "/{connectorNo}/sources/{sourceNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.source.manage")
    public ApiResult<SourceView> amend(@PathVariable final String connectorNo,
            @PathVariable final String sourceNo,
            @Valid @RequestBody final AmendSourceRequest request) {
        requireConnectorNo(connectorNo);
        requireSourceNo(sourceNo);
        return ApiResult.ok(SourceView.summary(sourceService.amend(AuthContext.subject(), connectorNo,
                sourceNo, request.endpoint(), request.credential())));
    }

    /** 源清单（属主或运营方；分页；不含自检结论）。 */
    @GetMapping(path = "/{connectorNo}/sources", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.read")
    public ApiResult<PageResult<SourceView>> list(@PathVariable final String connectorNo,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        requireConnectorNo(connectorNo);
        final PageQuery page = PageQuery.of(pageNum, pageSize, null);
        final List<SourceView> views = sourceService.list(AuthContext.subject(), isOperator(), connectorNo, page)
                .stream().map(SourceView::summary).toList();
        return ApiResult.ok(PageResult.of(views,
                sourceService.count(AuthContext.subject(), isOperator(), connectorNo), page));
    }

    /** 源详情（含最近一次自检结论，可空）。 */
    @GetMapping(path = "/{connectorNo}/sources/{sourceNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.read")
    public ApiResult<SourceView> detail(@PathVariable final String connectorNo,
            @PathVariable final String sourceNo) {
        requireConnectorNo(connectorNo);
        requireSourceNo(sourceNo);
        return ApiResult.ok(SourceView.of(sourceService.detail(AuthContext.subject(), isOperator(),
                connectorNo, sourceNo)));
    }

    /** 连通性自检（只读；200 业务结论：passed + 三类失败原因 + 可读文案）。 */
    @PostMapping(path = "/{connectorNo}/sources/{sourceNo}/checks",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.source.manage")
    public ApiResult<SourceCheckView> check(@PathVariable final String connectorNo,
            @PathVariable final String sourceNo) {
        requireConnectorNo(connectorNo);
        requireSourceNo(sourceNo);
        return ApiResult.ok(SourceCheckView.of(sourceNo,
                sourceService.check(AuthContext.subject(), connectorNo, sourceNo)));
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

    private static void requireSourceNo(final String sourceNo) {
        if (sourceNo == null || !SOURCE_NO.matcher(sourceNo).matches()) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
    }
}
