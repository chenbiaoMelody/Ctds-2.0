package com.ctds.metering.interfaces.billing;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.metering.application.billing.BillDetailView;
import com.ctds.metering.application.billing.BillSummary;
import com.ctds.metering.application.billing.BillingOutbillService;
import com.ctds.metering.application.billing.BillingPeriods;
import com.ctds.metering.application.billing.BillingQueryService;
import com.ctds.metering.domain.billing.BillSearchCriteria;
import com.ctds.metering.domain.billing.BillStatus;
import com.ctds.metering.domain.billing.BillType;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账单端点（hifi §1，全部 /api/v1/billing 前缀，ADR-005 统一响应）：
 * B-W1 手动出账 / B-R1 本方账单 / B-R2 账单详情 / B-R3 治理全量 / B-W2 确认 / B-W3 更正。
 * 归属校验在服务端（非本方与不存在同码同文 1011C0009）；运营面口径以 billing.governance 权限点收敛。
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingBillController {

    /** 运营角色标识（服务级 yml 角色映射口径，沿合约 / 连接器域先例）。 */
    private static final String ADMIN_ROLE = "admin";
    /** 合约号形态（CO+6 位，沿计量读面同口径）。 */
    private static final Pattern CONTRACT_NO = Pattern.compile("^[A-Z]{2}\\d{6}$");

    private final BillingOutbillService outbillService;
    private final BillingQueryService queryService;

    public BillingBillController(final BillingOutbillService outbillService,
            final BillingQueryService queryService) {
        this.outbillService = outbillService;
        this.queryService = queryService;
    }

    /** B-W1 手动即时出账（B10-1 手动腿；同合约同周期重复 → 409 1011C0010）。 */
    @PostMapping(path = "/bills", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("billing.governance")
    public ApiResult<BillDetailView> outbill(@RequestBody final OutbillRequest request) {
        final String billNo = outbillService.outbill(request.contractNo(), request.period(),
                request.agreedTransactionAmount(), AuthContext.subject());
        return ApiResult.ok(queryService.detail(billNo, AuthContext.subject(), true));
    }

    /** B-R1 本方账单分页（B12-1；myRole 标注需方 / 供方）。 */
    @GetMapping(path = "/bills/mine", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("billing.read")
    public ApiResult<PageResult<BillSummary>> mine(
            @RequestParam(required = false) final String contractNo,
            @RequestParam(required = false) final String period,
            @RequestParam(required = false) final Long productId,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final BillSearchCriteria criteria = new BillSearchCriteria(contractNo(contractNo),
                period(period), positive(productId), null, null, null, null);
        return ApiResult.ok(queryService.mine(AuthContext.subject(), criteria,
                PageQuery.of(pageNum, pageSize, null)));
    }

    /** B-R2 账单详情（本方或运营；非本方与不存在同码同文 1011C0009）。 */
    @GetMapping(path = "/bills/{billNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("billing.read")
    public ApiResult<BillDetailView> detail(@PathVariable final String billNo) {
        return ApiResult.ok(queryService.detail(billNo, AuthContext.subject(), isAdmin()));
    }

    /** B-R3 运营全量检索（B12-2；界面归 3.7.7）。 */
    @GetMapping(path = "/bills/governance", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("billing.governance")
    public ApiResult<PageResult<BillSummary>> governance(
            @RequestParam(required = false) final String contractNo,
            @RequestParam(required = false) final String period,
            @RequestParam(required = false) final Long productId,
            @RequestParam(required = false) final String requesterNo,
            @RequestParam(required = false) final String providerNo,
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final String billType,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final BillSearchCriteria criteria = new BillSearchCriteria(contractNo(contractNo),
                period(period), positive(productId), trim(requesterNo), trim(providerNo),
                status(status), billType(billType));
        return ApiResult.ok(queryService.governance(criteria, PageQuery.of(pageNum, pageSize, null)));
    }

    /** B-W2 运营确认（ISSUED → CONFIRMED；冻结 → 409 1011C0011）。 */
    @PostMapping(path = "/bills/{billNo}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("billing.governance")
    public ApiResult<BillDetailView> confirm(@PathVariable final String billNo) {
        return ApiResult.ok(queryService.confirm(billNo, AuthContext.subject()));
    }

    /** B-W3 更正单（红字冲正 / 补差；原单逐字不变，双向可溯）。 */
    @PostMapping(path = "/bills/{billNo}/corrections", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("billing.governance")
    public ApiResult<BillDetailView> correction(@PathVariable final String billNo,
            @RequestBody final CorrectionRequest request) {
        final BillDetailView view = queryService.correction(billNo, request.amount(),
                request.reason(), AuthContext.subject());
        return ApiResult.ok(view);
    }

    /** 当前角色是否运营（admin 角色 → billing.governance 权限点，服务级 yml 映射）。 */
    private static boolean isAdmin() {
        return AuthContext.roles().contains(ADMIN_ROLE);
    }

    private static String trim(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** contractNo 过滤：格式非法 → 1000C0001（hifi §1 注，与计量读面同口径）。 */
    private static String contractNo(final String value) {
        final String trimmed = trim(value);
        if (trimmed != null && !CONTRACT_NO.matcher(trimmed).matches()) {
            throw paramInvalid();
        }
        return trimmed;
    }

    private static Long positive(final Long value) {
        if (value != null && value < 1) {
            throw paramInvalid();
        }
        return value;
    }

    /** period 过滤：格式非法 → 1000C0001（B10-2 自然月口径）。 */
    private static String period(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        BillingPeriods.parse(value.trim());
        return value.trim();
    }

    private static BillStatus status(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return BillStatus.valueOf(value.trim());
        } catch (final IllegalArgumentException e) {
            throw paramInvalid();
        }
    }

    private static BillType billType(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return BillType.valueOf(value.trim());
        } catch (final IllegalArgumentException e) {
            throw paramInvalid();
        }
    }

    private static BizException paramInvalid() {
        return new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
    }

    /** 手动出账请求体（period 缺省当前自然月；分成档须携带约定交易额，Q3-A）。 */
    public record OutbillRequest(String contractNo, String period,
            BigDecimal agreedTransactionAmount) {
    }

    /** 更正单请求体（amount ≠ 0，负 = 红字冲正 / 正 = 补差；reason 必填）。 */
    public record CorrectionRequest(BigDecimal amount, String reason) {
    }
}
