package com.ctds.contract.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.domain.ClauseValues;
import com.ctds.contract.domain.Contract;
import com.ctds.contract.domain.ContractAction;
import com.ctds.contract.domain.ContractActionLog;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractClauseVersion;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.ContractRepository;
import com.ctds.contract.domain.ContractSignature;
import com.ctds.contract.domain.ContractStatus;
import com.ctds.contract.domain.DealTextCipher;
import com.ctds.contract.domain.DidPort;
import com.ctds.contract.domain.PartyRole;
import com.ctds.contract.domain.UsageControlPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 合约读面应用服务（WBS-3.4.3 hifi §2.2/§2.3/§2.4）：参与方读面（我的合约 / 详情含策略全文 /
 * 条款版本历史 / 签署核验）+ 治理读面（全量列表 / 治理详情含协商过程——每次查看写 GOVERNANCE_VIEW
 * 留痕，实际登录主体）+ 供 3.4.5 的策略读取方法（QC1，无 HTTP 端点）。
 *
 * <p>可见性（行为 7 规则 1/2）：合约与协商详情仅合约双方可见，非参与方与"不存在"同码同文逐字
 * （1008C0012 防枚举）+ DENIED_ACCESS 留痕；治理例外唯一（admin）。签署核验（R9）开放给
 * 合约双方与平台运营方（行为 3 规则 4）。签名值不出站（密文列只在应用层解密供验签调用）。</p>
 */
@Service
public class ContractQueryService {

    private static final Logger log = LoggerFactory.getLogger(ContractQueryService.class);
    private static final ObjectMapper VIEW_JSON_MAPPER = new ObjectMapper();
    /** 演示期平台运营方角色名（W12/R9 运营方腿；模板服务同款先例）。 */
    private static final String ADMIN_ROLE = "admin";

    private final ContractRepository repository;
    private final DealTextCipher dealTextCipher;
    private final DidPort didPort;
    private final Clock clock;

    public ContractQueryService(final ContractRepository repository,
            final DealTextCipher dealTextCipher, final DidPort didPort, final Clock clock) {
        this.repository = repository;
        this.dealTextCipher = dealTextCipher;
        this.didPort = didPort;
        this.clock = clock;
    }

    // ==== R6 我的合约分页（恒仅本主体参与；status/role 可选过滤）====

    public PageResult<MineItem> mine(final String operatorNo, final ContractStatus status,
            final PartyRole role, final PageQuery page) {
        final PageResult<Contract> result = repository.pageMine(operatorNo, status, role, page);
        final List<MineItem> items = result.list().stream()
                .map(contract -> MineItem.of(contract, operatorNo))
                .toList();
        return PageResult.of(items, result.total(), page);
    }

    /** R6 行载体（交易对手主体编号按本方角色解析）。 */
    public record MineItem(String contractNo, String productName, String counterpartySubjectNo,
            PartyRole myRole, String status, int currentClauseVersion, LocalDateTime effectiveAt,
            LocalDateTime updatedAt) {

        static MineItem of(final Contract contract, final String subjectNo) {
            final PartyRole myRole = contract.roleOf(subjectNo);
            final String counterparty = myRole == PartyRole.PROVIDER
                    ? contract.requesterSubjectNo() : contract.providerSubjectNo();
            return new MineItem(contract.contractNo(), contract.productName(), counterparty,
                    myRole, contract.status().name(), contract.currentClauseVersion(),
                    contract.effectiveAt(), contract.updatedAt());
        }
    }

    // ==== R7 合约详情（当前条款 + 策略全文 + 签署状态；响应字段集显式锚定——无数据本体）====

    public ContractDetail detail(final String contractNo, final String operatorNo) {
        final Contract contract = requireVisibleForRead(contractNo, operatorNo);
        return buildDetail(contract);
    }

    // ==== R8 条款版本历史（含变更明细"从何值→到何值"——协商过程默认可见对象 = 合约双方）====

    public List<ClauseVersionItem> clauseVersions(final String contractNo, final String operatorNo) {
        final Contract contract = requireVisibleForRead(contractNo, operatorNo);
        return repository.listVersions(contract.id()).stream()
                .map(this::toVersionItem)
                .toList();
    }

    // ==== R9 签署核验（双方 + 平台运营方；逐方经 did 三查；FAIL/UNAVAILABLE 异常处置留痕）====

    public VerificationReport verifySignatures(final String contractNo, final String operatorNo) {
        final Contract contract = requireVisibleForReadWithAdmin(contractNo, operatorNo);
        final List<ContractSignature> signatures = repository.listSignatures(contract.id());
        final LocalDateTime now = LocalDateTime.now(clock);
        final List<VerificationRow> results = signatures.stream().map(signature -> {
            final String dataBase64 = Base64.getEncoder().encodeToString(
                    signature.contentHash().getBytes(StandardCharsets.UTF_8));
            final String signatureBase64 = dealTextCipher.decrypt(signature.signatureCipher());
            final DidPort.DidVerifyResult verdict =
                    didPort.verify(signature.did(), dataBase64, signatureBase64);
            if (verdict.transportFailure()) {
                // did 传输不可达 → 整体 S0002（不冒充结论——hifi §2.2 R9 口径）
                throw new ContractBizException(ContractErrorCodes.DID_SERVICE_UNAVAILABLE,
                        ContractErrorCodes.DID_SERVICE_UNAVAILABLE_MESSAGE);
            }
            if (verdict.outcome() != DidPort.DidVerifyResult.Outcome.PASS) {
                // FAIL / UNAVAILABLE → 异常处置留痕（reason = C0018 / S0002 尾号——hifi §6.4）
                final var reasonCode = verdict.outcome() == DidPort.DidVerifyResult.Outcome.FAIL
                        ? ContractErrorCodes.SIGNATURE_IDENTITY_UNAVAILABLE
                        : ContractErrorCodes.DID_SERVICE_UNAVAILABLE;
                log.warn("签署核验未通过: contractNo={}, partyRole={}, outcome={}",
                        contractNo, signature.partyRole(), verdict.outcome());
                repository.insertLog(new ContractActionLog(null, contractNo,
                        contract.currentClauseVersion(), ContractAction.VERIFY_SIGNATURE_FAILED,
                        operatorNo, ContractErrorCodes.tailOf(reasonCode), signature.partyRole().name(),
                        verdict.outcome().name(), now));
            }
            return new VerificationRow(signature.partyRole().name(), signature.did(),
                    verdict.outcome().name(), verdict.reason(), now);
        }).toList();
        return new VerificationReport(contractNo, results);
    }

    /** R9 行载体（逐方结论；reason 为 did 服务侧原因码原文）。 */
    public record VerificationRow(String partyRole, String did, String result, String reason,
            LocalDateTime verifiedAt) {
    }

    /** R9 报告（逐方结论集；未签署 = 空结果集）。 */
    public record VerificationReport(String contractNo, List<VerificationRow> results) {
    }

    // ==== R10 治理列表 / R11 治理详情（每次查看写 GOVERNANCE_VIEW 留痕——实际登录主体）====

    public PageResult<Contract> governanceList(final ContractStatus status, final PageQuery page,
            final String operatorNo) {
        repository.insertLog(new ContractActionLog(null, null, null,
                ContractAction.GOVERNANCE_VIEW, operatorNo, null, null, null,
                LocalDateTime.now(clock)));
        return repository.pageAll(status, page);
    }

    public ContractDetail governanceDetail(final String contractNo, final String operatorNo) {
        // 治理详情按编号直取（不存在 → 1008C0012 同形；无越权腿——注解层已挡非 admin）
        final Contract contract = repository.findByNo(contractNo)
                .orElseThrow(() -> new ContractBizException(ContractErrorCodes.CONTRACT_NOT_VISIBLE,
                        ContractErrorCodes.CONTRACT_NOT_VISIBLE_MESSAGE));
        repository.insertLog(new ContractActionLog(null, contractNo, null,
                ContractAction.GOVERNANCE_VIEW, operatorNo, null, null, null,
                LocalDateTime.now(clock)));
        return buildDetail(contract);
    }

    /**
     * R11 治理详情的协商过程版本链（治理路径——无参与方门槛；**不另写查看留痕**：留痕由
     * {@link #governanceDetail} 每次调用单点承载，版本链为同一次治理查看的组成部分）。
     */
    public List<ClauseVersionItem> governanceClauseVersions(final String contractNo) {
        final Contract contract = repository.findByNo(contractNo)
                .orElseThrow(() -> new ContractBizException(ContractErrorCodes.CONTRACT_NOT_VISIBLE,
                        ContractErrorCodes.CONTRACT_NOT_VISIBLE_MESSAGE));
        return repository.listVersions(contract.id()).stream()
                .map(this::toVersionItem)
                .toList();
    }

    // ==== QC1 供 3.4.5 的策略读取（无 HTTP 端点，同宿主直调——移交-5）====

    /**
     * 生效中合约 = 策略 + 生效时间；未生效 = 空策略；已终止/已完结 = 状态可判（引擎据状态
     * 拦截"合约终止 → 策略同步失效"——剧本 C-4.3 S3-7 联动口径，引擎判定归 3.4.5）。
     * 合约不存在返回 null（引擎侧按无策略处置）。
     */
    public ContractStrategySnapshot loadEffectiveStrategy(final String contractNo) {
        final Contract contract = repository.findByNo(contractNo).orElse(null);
        if (contract == null) {
            return null;
        }
        final UsageControlPolicy strategy =
                contract.status() == ContractStatus.EFFECTIVE
                        ? currentStrategy(contract) : null;
        return new ContractStrategySnapshot(contract.contractNo(), contract.status().name(),
                contract.effectiveAt(), strategy);
    }

    /** QC1 快照（策略为解密值对象；status 为状态机现态名）。 */
    public record ContractStrategySnapshot(String contractNo, String status,
            LocalDateTime effectiveAt, UsageControlPolicy strategy) {
    }

    // ==== 详情视图载体（应用层装配——解密后的可读全文；出站字段集由 interfaces 锚定）====

    /** R7/R11 详情（策略全文在 clauseValues.strategy；签名不含签名值；存证事件摘要）。 */
    public record ContractDetail(Contract contract, ClauseValues clauseValues,
            String contentHash, List<SignatureItem> signatures, AttestationItem attestation) {
    }

    /** 逐方签署状态（DID + 签署时间——签名值不出站）。 */
    public record SignatureItem(String partyRole, String subjectNo, String did,
            LocalDateTime signedAt) {
    }

    /** 存证事件摘要（哈希 + 双方签署要素；未生效 = null）。 */
    public record AttestationItem(String contentHash, String providerDid,
            LocalDateTime providerSignedAt, String requesterDid, LocalDateTime requesterSignedAt,
            LocalDateTime attestedAt) {
    }

    /** 版本历史行（全文 + 变更明细；V1 变更明细 = null）。 */
    public record ClauseVersionItem(int versionNo, ClauseValues clauseValues,
            List<ClauseValues.Change> changes, String proposedBy, LocalDateTime proposedAt,
            LocalDateTime confirmedProviderAt, LocalDateTime confirmedRequesterAt) {
    }

    // ==== 内部：可见性与装配 ====

    /** 参与方可见性（行为 7 规则 1）：非参与方与不存在同码同文逐字 + DENIED_ACCESS 留痕。 */
    private Contract requireVisibleForRead(final String contractNo, final String operatorNo) {
        final Contract contract = repository.findByNo(contractNo).orElse(null);
        if (contract == null || contract.roleOf(operatorNo) == null) {
            denyAccessAndLog(contract, contractNo, operatorNo);
        }
        return contract;
    }

    /** R9 可见性（双方 + 平台运营方——行为 3 规则 4；非对象非 admin 同形拒绝 + 留痕）。 */
    private Contract requireVisibleForReadWithAdmin(final String contractNo,
            final String operatorNo) {
        final Contract contract = repository.findByNo(contractNo).orElse(null);
        if (contract == null || (contract.roleOf(operatorNo) == null
                && !AuthContext.roles().contains(ADMIN_ROLE))) {
            denyAccessAndLog(contract, contractNo, operatorNo);
        }
        return contract;
    }

    private void denyAccessAndLog(final Contract contract, final String contractNo,
            final String operatorNo) {
        repository.insertLog(new ContractActionLog(null,
                contract == null ? null : contractNo, null, ContractAction.DENIED_ACCESS,
                operatorNo, ContractErrorCodes.tailOf(ContractErrorCodes.CONTRACT_NOT_VISIBLE),
                null, null, LocalDateTime.now(clock)));
        throw new ContractBizException(ContractErrorCodes.CONTRACT_NOT_VISIBLE,
                ContractErrorCodes.CONTRACT_NOT_VISIBLE_MESSAGE);
    }

    private ContractDetail buildDetail(final Contract contract) {
        final ContractClauseVersion current = repository
                .findVersion(contract.id(), contract.currentClauseVersion()).orElse(null);
        final ClauseValues values = current == null ? null
                : storedClauseValues(current.clauseValuesCipher());
        final List<SignatureItem> signatures = repository.listSignatures(contract.id()).stream()
                .map(signature -> new SignatureItem(signature.partyRole().name(),
                        signature.subjectNo(), signature.did(), signature.signedAt()))
                .toList();
        final var attestation = repository.findAttestation(contract.id())
                .map(row -> new AttestationItem(row.contentHash(), row.providerDid(),
                        row.providerSignedAt(), row.requesterDid(), row.requesterSignedAt(),
                        row.attestedAt()))
                .orElse(null);
        return new ContractDetail(contract, values, current == null ? null : current.contentHash(),
                signatures, attestation);
    }

    private ClauseVersionItem toVersionItem(final ContractClauseVersion version) {
        final List<ClauseValues.Change> changes =
                version.changesCipher() == null ? null
                        : ClauseChangesCodec.decode(dealTextCipher.decrypt(version.changesCipher()));
        return new ClauseVersionItem(version.versionNo(),
                storedClauseValues(version.clauseValuesCipher()), changes, version.proposedBy(),
                version.proposedAt(), version.confirmedProviderAt(),
                version.confirmedRequesterAt());
    }

    /** 生效中合约的策略读取（QC1 内部：解密当前锁定版本条款值取策略）。 */
    private UsageControlPolicy currentStrategy(final Contract contract) {
        final ContractClauseVersion current = repository
                .findVersion(contract.id(), contract.currentClauseVersion()).orElse(null);
        return current == null ? null
                : storedClauseValues(current.clauseValuesCipher()).strategy();
    }

    private ClauseValues storedClauseValues(final byte[] cipher) {
        try {
            return ClauseValues.fromStored(VIEW_JSON_MAPPER.readTree(dealTextCipher.decrypt(cipher)));
        } catch (final com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalStateException("合约密文回读解析失败（存储层已校验合法）", e);
        }
    }

    /** 变更明细解码（[{slot, from, to}] JSON 数组 → 值对象列表——hifi §4 表 2 形态）。 */
    static final class ClauseChangesCodec {

        static List<ClauseValues.Change> decode(final String json) {
            try {
                final var array = VIEW_JSON_MAPPER.readTree(json);
                final List<ClauseValues.Change> changes = new java.util.ArrayList<>();
                for (final var node : array) {
                    changes.add(new ClauseValues.Change(node.path("slot").asText(),
                            node.hasNonNull("from") ? node.get("from").asText() : null,
                            node.hasNonNull("to") ? node.get("to").asText() : null));
                }
                return changes;
            } catch (final com.fasterxml.jackson.core.JacksonException e) {
                throw new IllegalStateException("变更明细密文回读解析失败（存储层已校验合法）", e);
            }
        }

        private ClauseChangesCodec() {
        }
    }
}
