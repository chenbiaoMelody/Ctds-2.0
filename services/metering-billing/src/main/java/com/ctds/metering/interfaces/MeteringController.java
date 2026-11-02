package com.ctds.metering.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.metering.application.CollectOutcome;
import com.ctds.metering.application.ContractFlow;
import com.ctds.metering.application.ContractSummary;
import com.ctds.metering.application.MeteringCollectService;
import com.ctds.metering.application.MeteringQueryService;
import com.ctds.metering.application.ReconcileSnapshot;
import com.ctds.metering.domain.FlowSearchCriteria;
import com.ctds.metering.domain.MeteringFlowRow;
import com.ctds.metering.domain.MeteringSourceType;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计量读面端点（WBS-3.7.5 hifi §1，全部 /api/v1/metering 前缀，ADR-005 统一响应）：
 * R-M0 触发采集（运营）/ R-M1 本方流水 / R-M2 本方聚合 / R-M3 四方勾稽读数（运营）。
 * WBS-3.7.7 加性只读端点 R-M4 运营全量逐条流水（B12-2 归口；与 R-M1 归属硬校验并存互不影响）。
 * 身份取 AuthContext（网关注入/演示期身份头）；本方读面可见性判定在服务端（B8-4）。
 */
@RestController
@RequestMapping("/api/v1/metering")
public class MeteringController {

    /** 合约号形态（CO+6 位，沿本方读面与计费域同口径）。 */
    private static final Pattern CONTRACT_NO = Pattern.compile("^[A-Z]{2}\\d{6}$");
    /** 时间区间入参格式（R-M4 hifi §1：yyyy-MM-dd HH:mm:ss，前端 datetime 控件同型）。 */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MeteringCollectService collectService;
    private final MeteringQueryService queryService;

    public MeteringController(final MeteringCollectService collectService,
            final MeteringQueryService queryService) {
        this.collectService = collectService;
        this.queryService = queryService;
    }

    /** R-M0 触发通道 2 增量采集（运营；可重复触发，重复 collected = 0 幂等）。 */
    @PostMapping(path = "/collect", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("metering.governance")
    public ApiResult<CollectOutcome> collect() {
        return ApiResult.ok(collectService.collect());
    }

    /** R-M1 本方合约计量流水（B7-1 读面 + B8-4 归属校验服务端强制）。 */
    @GetMapping(path = "/contracts/{contractNo}/flows", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("metering.read")
    public ApiResult<ContractFlow> flows(@PathVariable final String contractNo) {
        return ApiResult.ok(queryService.flows(contractNo, AuthContext.subject()));
    }

    /** R-M2 本方合约按自然月聚合（B8-1 查询承载，Q2-A/Q3-A）。 */
    @GetMapping(path = "/contracts/{contractNo}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("metering.read")
    public ApiResult<ContractSummary> summary(@PathVariable final String contractNo) {
        return ApiResult.ok(queryService.summary(contractNo, AuthContext.subject()));
    }

    /** R-M3 四方勾稽读数（运营治理读面；纯读数展示，不做归属校验）。 */
    @GetMapping(path = "/contracts/{contractNo}/reconcile",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("metering.governance")
    public ApiResult<ReconcileSnapshot> reconcile(@PathVariable final String contractNo) {
        return ApiResult.ok(queryService.reconcile(contractNo));
    }

    /**
     * R-M4 运营全量逐条流水（WBS-3.7.7 hifi §1；B12-2）：五维过滤 + 分页，
     * occurred_at 倒序稳定分页；时间区间闭区间。参数非法一律 1000C0001（零新错误码）。
     */
    @GetMapping(path = "/flows/governance", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("metering.governance")
    public ApiResult<PageResult<MeteringFlowRow>> governanceFlows(
            @RequestParam(required = false) final String contractNo,
            @RequestParam(required = false) final String requesterNo,
            @RequestParam(required = false) final String sourceType,
            @RequestParam(required = false) final String from,
            @RequestParam(required = false) final String to,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final FlowSearchCriteria criteria = new FlowSearchCriteria(
                contractNo(contractNo), trim(requesterNo), sourceType(sourceType),
                time(from), time(to));
        if (criteria.from() != null && criteria.to() != null
                && criteria.from().isAfter(criteria.to())) {
            throw paramInvalid();
        }
        return ApiResult.ok(queryService.governanceFlows(criteria,
                PageQuery.of(pageNum, pageSize, null)));
    }

    private static String trim(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** 合约号过滤：形态非法 → 1000C0001（与计费域治理检索同口径）。 */
    private static String contractNo(final String value) {
        final String trimmed = trim(value);
        if (trimmed != null && !CONTRACT_NO.matcher(trimmed).matches()) {
            throw paramInvalid();
        }
        return trimmed;
    }

    /** 源类型过滤：非四值枚举 → 1000C0001（值域封闭，MeteringSourceType）。 */
    private static MeteringSourceType sourceType(final String value) {
        final String trimmed = trim(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return MeteringSourceType.valueOf(trimmed);
        } catch (final IllegalArgumentException e) {
            throw paramInvalid();
        }
    }

    /** 时间过滤：格式非法 → 1000C0001（服务端文案不暴露解析细节）。 */
    private static LocalDateTime time(final String value) {
        final String trimmed = trim(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(trimmed, TIME_FORMAT);
        } catch (final DateTimeParseException e) {
            throw paramInvalid();
        }
    }

    private static BizException paramInvalid() {
        return new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
    }
}
