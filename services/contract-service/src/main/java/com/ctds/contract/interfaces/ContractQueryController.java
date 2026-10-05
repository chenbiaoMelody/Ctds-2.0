package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.application.ContractQueryService;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.ContractStatus;
import com.ctds.contract.domain.PartyRole;
import com.ctds.contract.interfaces.dto.DealViews;
import com.ctds.contract.interfaces.dto.PageViews;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合约参与方读面端点（WBS-3.4.3 hifi §2.2 R6~R9；R9 核验为 POST 动作）。注解权限点
 * {@code contract.read}（admin 亦持——R9 运营方腿）；**参与方判定在应用服务单点**：
 * 非参与方与"不存在"同码同文逐字（1008C0012 防枚举）+ DENIED_ACCESS 留痕。
 */
@RestController
@RequestMapping("/api/v1/contracts")
public class ContractQueryController {

    private final ContractQueryService queryService;

    public ContractQueryController(final ContractQueryService queryService) {
        this.queryService = queryService;
    }

    /** R6 我的合约分页（恒仅本主体参与；status/role 可选过滤）。 */
    @GetMapping(path = "/mine", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.read")
    public ApiResult<PageResult<DealViews.Mine>> mine(
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final String role,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<ContractQueryService.MineItem> result = queryService.mine(
                AuthContext.subject(), parseStatus(status), parseRole(role),
                PageQuery.of(pageNum, pageSize, null));
        return ApiResult.ok(PageViews.page(result, item -> new DealViews.Mine(
                item.contractNo(), item.productName(), item.counterpartySubjectNo(),
                item.myRole().name(), item.status(), item.currentClauseVersion(),
                item.effectiveAt(), item.updatedAt())));
    }

    /** R7 合约详情（当前条款 + 策略全文 + 签署状态；字段集锚定无数据本体——剧本 C-4.3 S1-2 策略视图）。 */
    @GetMapping(path = "/{contractNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.read")
    public ApiResult<DealViews.Detail> detail(@PathVariable final String contractNo) {
        return ApiResult.ok(DealViews.detailOf(
                queryService.detail(contractNo, AuthContext.subject())));
    }

    /** R8 条款版本历史（逐版全文 + 变更明细"从何值→到何值"——协商过程默认可见对象）。 */
    @GetMapping(path = "/{contractNo}/clause-versions",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.read")
    public ApiResult<List<DealViews.ClauseVersion>> clauseVersions(
            @PathVariable final String contractNo) {
        return ApiResult.ok(queryService.clauseVersions(contractNo, AuthContext.subject())
                .stream().map(DealViews::versionOf).toList());
    }

    /** R9 签署核验（双方 + 运营方；逐方经 did 三查；FAIL/UNAVAILABLE 异常处置留痕）。 */
    @PostMapping(path = "/{contractNo}/signature-verifications",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.read")
    public ApiResult<DealViews.Verification> verifySignatures(
            @PathVariable final String contractNo) {
        final var report = queryService.verifySignatures(contractNo, AuthContext.subject());
        return ApiResult.ok(new DealViews.Verification(report.contractNo(),
                report.results().stream().map(row -> new DealViews.Verification.Row(
                        row.partyRole(), row.did(), row.result(), row.reason(), row.verifiedAt()))
                        .toList()));
    }

    /** status 过滤参数解析（非法值 400 通用——沿 catalog 检索参数口径）。 */
    private ContractStatus parseStatus(final String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ContractStatus.valueOf(status);
        } catch (final IllegalArgumentException e) {
            throw new ContractBizException(ContractErrorCodes.CONTRACT_PARAM_INVALID,
                    ContractErrorCodes.TEMPLATE_PARAM_INVALID_MESSAGE);
        }
    }

    /** role 过滤参数解析（PROVIDER/REQUESTER；非法值 400 通用）。 */
    private PartyRole parseRole(final String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        try {
            return PartyRole.valueOf(role);
        } catch (final IllegalArgumentException e) {
            throw new ContractBizException(ContractErrorCodes.CONTRACT_PARAM_INVALID,
                    ContractErrorCodes.TEMPLATE_PARAM_INVALID_MESSAGE);
        }
    }
}
