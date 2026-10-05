package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.application.ContractCommandService;
import com.ctds.contract.application.ContractQueryService;
import com.ctds.contract.domain.Contract;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.ContractStatus;
import com.ctds.contract.interfaces.dto.DealRequests;
import com.ctds.contract.interfaces.dto.DealViews;
import com.ctds.contract.interfaces.dto.PageViews;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合约治理面端点（WBS-3.4.3 hifi §2.3 W12 + R10/R11）。注解权限点
 * {@code contract.governance} 仅 admin 持（provider 在注解层 403 无留痕——治理面越权腿由
 * 持 deal 的参与方对"他人合约"的越权承载，见 hifi §2.3 权限注）；W12 应用层另做 admin 判定
 * （C0011 + 拒绝留痕——注解之外越权腿）；治理读面每次调用写 GOVERNANCE_VIEW 留痕（实际登录
 * 主体——沿 DB-29/目录域先例）。
 */
@RestController
@RequestMapping("/api/v1/contracts/governance")
public class ContractGovernanceController {

    private final ContractCommandService commandService;
    private final ContractQueryService queryService;

    public ContractGovernanceController(final ContractCommandService commandService,
            final ContractQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /** W12 强制终止（理由必填 1~512；仅 EFFECTIVE；留痕含理由与操作者——剧本 S3-3）。 */
    @PostMapping(path = "/{contractNo}/force-termination",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.governance")
    public ApiResult<DealViews.Terminated> forceTermination(@PathVariable final String contractNo,
            @RequestBody final DealRequests.ForceTermination request) {
        final var result = commandService.forceTerminate(contractNo, AuthContext.subject(),
                request.reason());
        return ApiResult.ok(new DealViews.Terminated(result.contractNo(), result.status(),
                result.terminationType()));
    }

    /** R10 治理全量列表（含协商中/已终止；每次调用写 GOVERNANCE_VIEW 留痕——剧本 S3-2）。 */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.governance")
    public ApiResult<PageResult<ContractGovernanceItem>> list(
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<Contract> result = queryService.governanceList(parseStatus(status),
                PageQuery.of(pageNum, pageSize, null), AuthContext.subject());
        return ApiResult.ok(PageViews.page(result, ContractGovernanceItem::of));
    }

    /** R11 治理详情（合约全文 + 协商过程〔版本链〕 + 签署状态 + 存证事件；查看留痕）。 */
    @GetMapping(path = "/{contractNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.governance")
    public ApiResult<GovernanceDetail> detail(@PathVariable final String contractNo) {
        final var detail = queryService.governanceDetail(contractNo, AuthContext.subject());
        final List<DealViews.ClauseVersion> versions = queryService
                .governanceClauseVersions(contractNo)
                .stream().map(DealViews::versionOf).toList();
        return ApiResult.ok(new GovernanceDetail(DealViews.detailOf(detail), versions));
    }

    /** R11 载体（详情 + 协商过程版本链）。 */
    public record GovernanceDetail(DealViews.Detail detail, List<DealViews.ClauseVersion>
            clauseVersions) {
    }

    /** 治理列表行（全量视角：双方 + 状态 + 终止类型 + 生效时间）。 */
    public record ContractGovernanceItem(String contractNo, String productName,
            String providerSubjectNo, String requesterSubjectNo, String status,
            String terminationType, int currentClauseVersion, LocalDateTime effectiveAt,
            LocalDateTime updatedAt) {

        static ContractGovernanceItem of(final Contract contract) {
            return new ContractGovernanceItem(contract.contractNo(), contract.productName(),
                    contract.providerSubjectNo(), contract.requesterSubjectNo(),
                    contract.status().name(), contract.terminationType() == null
                            ? null : contract.terminationType().name(),
                    contract.currentClauseVersion(), contract.effectiveAt(), contract.updatedAt());
        }
    }

    /** status 过滤参数解析（非法值 400 通用）。 */
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
}
