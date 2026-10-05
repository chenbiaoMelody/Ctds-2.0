package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.crypto.Sm3Service;
import com.ctds.contract.application.ContractCommandService;
import com.ctds.contract.application.InitiateCommand;
import com.ctds.contract.application.InitiateResult;
import com.ctds.contract.domain.ClauseValues;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.interfaces.dto.DealRequests;
import com.ctds.contract.interfaces.dto.DealViews;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合约参与方写面端点（WBS-3.4.3 hifi §2.1 W5~W11；编号续 3.4.2）。注解权限点
 * {@code contract.deal} = 功能第一道门槛（admin 亦持——使"非参与方 admin 尝试业务写"能进
 * 应用层并被拒绝留痕，而非注解层静默 403）；**可见性/参与方/资格/状态机判定在应用服务单点**
 * （hifi §2.1 权限注）。requestFingerprint 由控制器按条款值稳定哈希预计算（服务端派生幂等键）。
 */
@RestController
@RequestMapping("/api/v1/contracts")
public class ContractCommandController {

    private final ContractCommandService commandService;
    private final Sm3Service sm3Service;

    public ContractCommandController(final ContractCommandService commandService,
            final Sm3Service sm3Service) {
        this.commandService = commandService;
        this.sm3Service = sm3Service;
    }

    /** W5 发起合约（需求方；201 = 发起即协商中，首版条款 V1 随发起落库）。 */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("contract.deal")
    public ApiResult<DealViews.Initiated> initiate(
            @RequestBody final DealRequests.InitiateContract request) {
        final var cmd = new InitiateCommand(AuthContext.subject(), request.productId(),
                request.templateNo(), requireVersionNo(request.templateVersionNo()),
                request.clauseValues(), fingerprint(request.clauseValues()));
        final InitiateResult result = commandService.initiate(cmd);
        return ApiResult.ok(new DealViews.Initiated(result.contractNo(), result.status(),
                result.clauseVersionNo(), result.templateNo(), result.templateVersionNo(),
                result.productId()));
    }

    /** W6 提案/反提案（201 = 新版本行；变更明细"从何值→到何值"随版本行落库）。 */
    @PostMapping(path = "/{contractNo}/proposals", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("contract.deal")
    public ApiResult<DealViews.Proposed> propose(@PathVariable final String contractNo,
            @RequestBody final DealRequests.ProposeClause request) {
        final var result = commandService.propose(contractNo, request.clauseValues(),
                AuthContext.subject());
        return ApiResult.ok(new DealViews.Proposed(result.contractNo(), result.clauseVersionNo(),
                result.previousVersionNo()));
    }

    /** W7 确认当前条款版本（空请求体；双方齐 → 条款锁定转待签署）。 */
    @PostMapping(path = "/{contractNo}/confirmations", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.deal")
    public ApiResult<DealViews.Confirmed> confirm(@PathVariable final String contractNo) {
        final var result = commandService.confirm(contractNo, AuthContext.subject());
        return ApiResult.ok(new DealViews.Confirmed(result.contractNo(), result.clauseVersionNo(),
                new DealViews.Confirmed.Confirmations(result.providerConfirmed(),
                        result.requesterConfirmed()), result.status()));
    }

    /** W8 协商终止（空请求体；NEGOTIATING 态任一方；终态不可逆）。 */
    @PostMapping(path = "/{contractNo}/negotiation-terminations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.deal")
    public ApiResult<DealViews.Terminated> terminateNegotiation(
            @PathVariable final String contractNo) {
        final var result = commandService.terminateNegotiation(contractNo, AuthContext.subject());
        return ApiResult.ok(new DealViews.Terminated(result.contractNo(), result.status(),
                result.terminationType()));
    }

    /** W9 电子签署（内容 = 锁定版本内容哈希，经 did 演示签名入口代签；后签 → 已生效 + 存证）。 */
    @PostMapping(path = "/{contractNo}/signatures", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("contract.deal")
    public ApiResult<DealViews.Signed> sign(@PathVariable final String contractNo,
            @RequestBody final DealRequests.Sign request) {
        final var result = commandService.sign(contractNo, request.did(), AuthContext.subject());
        return ApiResult.ok(new DealViews.Signed(result.contractNo(), result.partyRole(),
                result.status(), result.effectiveAt()));
    }

    /** W10 拒签终止（空请求体；未双签生效前任一方——含已签方撤回，Q4-A）。 */
    @PostMapping(path = "/{contractNo}/signature-refusals",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.deal")
    public ApiResult<DealViews.Terminated> refuseSignature(@PathVariable final String contractNo) {
        final var result = commandService.refuseSignature(contractNo, AuthContext.subject());
        return ApiResult.ok(new DealViews.Terminated(result.contractNo(), result.status(),
                result.terminationType()));
    }

    /** W11 合意解除确认（空请求体；仅已生效；双方齐 → 已完结终态）。 */
    @PostMapping(path = "/{contractNo}/release-consents",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.deal")
    public ApiResult<DealViews.Released> releaseConsent(@PathVariable final String contractNo) {
        final var result = commandService.releaseConsent(contractNo, AuthContext.subject());
        return ApiResult.ok(new DealViews.Released(result.contractNo(), result.status(),
                new DealViews.Released.ReleaseConsents(result.providerConsented(),
                        result.requesterConsented())));
    }

    /** 条款值稳定哈希（幂等键成分——槽位键排序 + 策略固定字段序的存储形态 SM3，服务端派生）。 */
    private String fingerprint(final JsonNode clauseValues) {
        return sm3Service.digestHex(
                ClauseValues.fromStored(clauseValues).toStorageText());
    }

    /** 模板版本号要素（1008C0008：缺失/非整数在绑定层或此处拦截）。 */
    private int requireVersionNo(final Integer templateVersionNo) {
        if (templateVersionNo == null || templateVersionNo < 1) {
            throw new ContractBizException(ContractErrorCodes.CONTRACT_PARAM_INVALID,
                    ContractErrorCodes.TEMPLATE_PARAM_INVALID_MESSAGE);
        }
        return templateVersionNo;
    }
}
