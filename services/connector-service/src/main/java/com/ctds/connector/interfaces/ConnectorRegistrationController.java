package com.ctds.connector.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.connector.application.ConnectorAmendCommand;
import com.ctds.connector.application.ConnectorQueryService;
import com.ctds.connector.application.ConnectorRegistrationCommand;
import com.ctds.connector.application.ConnectorRegistrationService;
import com.ctds.connector.application.ConnectorVerificationService;
import com.ctds.connector.application.VerificationOutcome;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.interfaces.dto.ConnectorRegisterRequest;
import com.ctds.connector.interfaces.dto.ConnectorViews.RegistrationView;
import com.ctds.connector.interfaces.dto.ConnectorViews.VerificationResultView;
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
 * 注册申请与核验端点（hifi §5 #1~#6）。
 * 身份一律取 {@link AuthContext}（请求体不含主体编号）；运营方 = admin 角色（核验面 connector.review 持有者）。
 */
@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorRegistrationController {

    private static final Pattern APPLICATION_NO = Pattern.compile("^CA\\d{6}$");
    private static final String OPERATOR_ROLE = "admin";

    private final ConnectorRegistrationService registrationService;
    private final ConnectorVerificationService verificationService;
    private final ConnectorQueryService queryService;

    public ConnectorRegistrationController(final ConnectorRegistrationService registrationService,
            final ConnectorVerificationService verificationService,
            final ConnectorQueryService queryService) {
        this.registrationService = registrationService;
        this.verificationService = verificationService;
        this.queryService = queryService;
    }

    /** 提交注册申请（行为 6 验收 1~3）。 */
    @PostMapping(path = "/registrations", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.register")
    public ApiResult<RegistrationView> apply(@Valid @RequestBody final ConnectorRegisterRequest request) {
        final ConnectorRegistration registration = registrationService.apply(
                new ConnectorRegistrationCommand(AuthContext.subject(), request.connectorName(),
                        request.connectorType(), request.connectorVersion()));
        return ApiResult.ok(RegistrationView.of(registration));
    }

    /** 补正要素（行为 7 验收 4；仅属主 + 仅待核验态）。 */
    @PutMapping(path = "/registrations/{applicationNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.register")
    public ApiResult<RegistrationView> amend(@PathVariable final String applicationNo,
            @Valid @RequestBody final ConnectorRegisterRequest request) {
        requireApplicationNo(applicationNo);
        final ConnectorRegistration registration = registrationService.amend(
                new ConnectorAmendCommand(AuthContext.subject(), applicationNo, request.connectorName(),
                        request.connectorType(), request.connectorVersion()));
        return ApiResult.ok(RegistrationView.of(registration));
    }

    /** 我的申请列表（分页，申请时间倒序）。 */
    @GetMapping(path = "/registrations/mine", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.read")
    public ApiResult<PageResult<RegistrationView>> myRegistrations(
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageQuery page = PageQuery.of(pageNum, pageSize, null);
        final String subjectNo = AuthContext.subject();
        final List<RegistrationView> views = queryService.myRegistrations(subjectNo, page).stream()
                .map(RegistrationView::of).toList();
        return ApiResult.ok(PageResult.of(views, queryService.countMyRegistrations(subjectNo), page));
    }

    /** 单申请详情（属主或运营方；非属主同码同文防枚举）。 */
    @GetMapping(path = "/registrations/{applicationNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.read")
    public ApiResult<RegistrationView> registration(@PathVariable final String applicationNo) {
        requireApplicationNo(applicationNo);
        return ApiResult.ok(RegistrationView.of(queryService.registration(AuthContext.subject(),
                isOperator(), applicationNo)));
    }

    /** 待核验队列（运营方；申请时间升序）。 */
    @GetMapping(path = "/review/queue", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.review")
    public ApiResult<PageResult<RegistrationView>> reviewQueue(
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageQuery page = PageQuery.of(pageNum, pageSize, null);
        final List<RegistrationView> views = queryService.reviewQueue(page).stream()
                .map(RegistrationView::of).toList();
        return ApiResult.ok(PageResult.of(views, queryService.countReviewQueue(), page));
    }

    /** 执行核验（三查；通过 = 转已注册 + 签发编号 + 建绑定；拒绝 = 200 结论 + 留痕）。 */
    @PostMapping(path = "/registrations/{applicationNo}/verification",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.review")
    public ApiResult<VerificationResultView> verify(@PathVariable final String applicationNo) {
        requireApplicationNo(applicationNo);
        final VerificationOutcome outcome = verificationService.verify(AuthContext.subject(), applicationNo);
        return ApiResult.ok(VerificationResultView.of(outcome));
    }

    private static boolean isOperator() {
        return AuthContext.roles().contains(OPERATOR_ROLE);
    }

    private static void requireApplicationNo(final String applicationNo) {
        if (applicationNo == null || !APPLICATION_NO.matcher(applicationNo).matches()) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
    }
}
