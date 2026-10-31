package com.ctds.contract.application;

import com.ctds.contract.domain.CatalogProductPort;
import com.ctds.contract.domain.ClauseValues;
import com.ctds.contract.domain.Contract;
import com.ctds.contract.domain.ContractAction;
import com.ctds.contract.domain.ContractActionLog;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.ContractRepository;
import com.ctds.contract.domain.ContractStatus;
import com.ctds.contract.domain.ContractClauseVersion;
import com.ctds.contract.domain.DealTextCipher;
import com.ctds.contract.domain.SubjectAdmission;
import com.ctds.contract.domain.SubjectAdmissionPort;
import com.ctds.contract.domain.TemplateVersion;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 合约发起门槛链与快照落库（WBS-3.7.3 hifi §3.1 行为等价提取：自 ContractCommandService.initiate
 * 平移，W5 行为 / 幂等 / 错误码零变更）。两段式供两个入口复用：
 * <ul>
 * <li>W5 对外发起（{@code ContractCommandService.initiate}，@Idempotent 注解原地保留——
 * 内容幂等键含条款指纹、TTL 内返回首次结果）；</li>
 * <li>订单专用通路（{@code OrderCommandService}，prepare + 事务内 persist——<b>不经过</b> W5
 * 内容幂等注解：订单关闭后同内容重购须生成新合约，不得命中旧合约缓存，Q6-A）。</li>
 * </ul>
 *
 * <p>门槛链顺序固定（3.4.3 hifi §5.1）：① 资格 ADMITTED → ② 产品事实三态（LISTED 硬门槛）
 * → ③ 双方异主体 → ④ 模板 QV1/QV2 → ⑤ 条款值校验（槽位框架 + 策略五要素）；persist =
 * ⑥ 取号 + contract + 条款版本 V1 + CREATE 留痕（快照锁定三写，事务加入调用方外层）。</p>
 */
@Service
public class ContractInitiationService {

    private static final Logger log = LoggerFactory.getLogger(ContractInitiationService.class);
    /** 合约编号前缀（Q1-A：CO + 6 位全局序号；自 ContractCommandService 平移）。 */
    private static final String CONTRACT_NO_PREFIX = "CO";
    /** 产品在架状态硬门槛（行为 2 规则 2；状态原值来自 catalog 内部端点）。 */
    private static final String STATUS_LISTED = "LISTED";

    private final ContractRepository repository;
    private final TemplateQueryService templateQueryService;
    private final SubjectAdmissionPort subjectAdmissionPort;
    private final CatalogProductPort catalogProductPort;
    private final DealTextCipher dealTextCipher;
    private final Clock clock;

    public ContractInitiationService(final ContractRepository repository,
            final TemplateQueryService templateQueryService,
            final SubjectAdmissionPort subjectAdmissionPort,
            final CatalogProductPort catalogProductPort,
            final DealTextCipher dealTextCipher, final Clock clock) {
        this.repository = repository;
        this.templateQueryService = templateQueryService;
        this.subjectAdmissionPort = subjectAdmissionPort;
        this.catalogProductPort = catalogProductPort;
        this.dealTextCipher = dealTextCipher;
        this.clock = clock;
    }

    /**
     * 发起命令的准备结果（①~⑤ 通过后的锁定快照集）：product = 产品事实（快照源）、
     * frameworkVersion = 锁定的模板版本（框架全文；loadFramework 返回的版本行）、
     * parsed = 条款值解析结果（密文存储源）。
     */
    public record PreparedInitiation(InitiateCommand cmd,
            CatalogProductPort.CatalogProduct product, TemplateVersion frameworkVersion,
            ClauseValues.Parsed parsed) {
    }

    /** 门槛链①~⑤（事务外；详见类注释顺序——3.4.3 hifi §5.1 固定）。 */
    public PreparedInitiation prepare(final InitiateCommand cmd) {
        // ① 主体资格（ADMITTED 硬门槛；1008C0003 防枚举 / 1008S0001 不冒充）
        requireAdmitted(cmd.requesterNo(), null,
                ContractErrorCodes.INITIATE_ADMISSION_REQUIRED_MESSAGE);
        // ② 产品事实三态（1008C0010 同码同文防枚举 / 1008S0003 不冒充）
        final CatalogProductPort.CatalogProductResult productResult =
                catalogProductPort.fetch(cmd.productId());
        if (productResult.state() == CatalogProductPort.CatalogProductResult.State.UNAVAILABLE) {
            throw new ContractBizException(ContractErrorCodes.CATALOG_SERVICE_UNAVAILABLE,
                    ContractErrorCodes.CATALOG_SERVICE_UNAVAILABLE_MESSAGE);
        }
        if (productResult.state() == CatalogProductPort.CatalogProductResult.State.NOT_FOUND
                || !STATUS_LISTED.equals(productResult.product().status())) {
            throw new ContractBizException(ContractErrorCodes.PRODUCT_NOT_AVAILABLE_FOR_DEAL,
                    ContractErrorCodes.PRODUCT_NOT_AVAILABLE_FOR_DEAL_MESSAGE);
        }
        final CatalogProductPort.CatalogProduct product = productResult.product();
        // ③ 双方须为不同主体（剧本 S1-6）
        if (product.providerSubjectNo().equals(cmd.requesterNo())) {
            throw new ContractBizException(ContractErrorCodes.SELF_DEAL_FORBIDDEN,
                    ContractErrorCodes.SELF_DEAL_FORBIDDEN_MESSAGE);
        }
        // ④ 模板 QV1（无效三态出站统一 C0017——明细入日志）+ QV2 锁定版本框架全文
        final InitiationCheck check = templateQueryService.validateForInitiation(cmd.templateNo(),
                cmd.templateVersionNo());
        if (!check.valid()) {
            log.info("模板不可用于发起: templateNo={}, versionNo={}, reason={}",
                    cmd.templateNo(), cmd.templateVersionNo(), check.invalidReason());
            throw new ContractBizException(
                    ContractErrorCodes.TEMPLATE_NOT_AVAILABLE_FOR_INITIATION,
                    ContractErrorCodes.TEMPLATE_NOT_AVAILABLE_FOR_INITIATION_MESSAGE);
        }
        final var frameworkVersion = templateQueryService
                .loadFramework(cmd.templateNo(), cmd.templateVersionNo())
                .orElseThrow(() -> new ContractBizException(
                        ContractErrorCodes.TEMPLATE_NOT_AVAILABLE_FOR_INITIATION,
                        ContractErrorCodes.TEMPLATE_NOT_AVAILABLE_FOR_INITIATION_MESSAGE));
        // ⑤ 条款值校验（槽位框架 C0014 / 策略基础取值 C0015——明细入日志，响应仅常量文案）
        final ClauseValues.Parsed parsed = ClauseValues.parse(cmd.clauseValues());
        if (parsed.hasSlotViolations()) {
            log.info("条款值与模板框架不符: productId={}, violations={}", cmd.productId(),
                    parsed.slotViolations());
            throw new ContractBizException(ContractErrorCodes.CLAUSE_VALUES_INVALID,
                    ContractErrorCodes.CLAUSE_VALUES_INVALID_MESSAGE);
        }
        // 策略校验单点全查（解析结果即违规清单——发起路径不再二次取值校验）
        if (parsed.hasStrategyViolations()) {
            log.info("策略条款不符合使用控制约定: productId={}, violations={}", cmd.productId(),
                    parsed.strategyViolations());
            throw new ContractBizException(ContractErrorCodes.POLICY_CLAUSE_INVALID,
                    ContractErrorCodes.POLICY_CLAUSE_INVALID_MESSAGE);
        }
        final List<String> frameworkViolations =
                parsed.values().frameworkViolations(frameworkVersion.clauseFrameworkJson());
        if (!frameworkViolations.isEmpty()) {
            log.info("条款值与模板框架不符: productId={}, violations={}", cmd.productId(),
                    frameworkViolations);
            throw new ContractBizException(ContractErrorCodes.CLAUSE_VALUES_INVALID,
                    ContractErrorCodes.CLAUSE_VALUES_INVALID_MESSAGE);
        }
        return new PreparedInitiation(cmd, product, frameworkVersion, parsed);
    }

    /** 快照锁定三写（⑥；调用方事务内——取号/合约/版本 V1/留痕与调用方写面原子提交）。 */
    public InitiateResult persist(final PreparedInitiation prepared) {
        final InitiateCommand cmd = prepared.cmd();
        final CatalogProductPort.CatalogProduct product = prepared.product();
        final LocalDateTime now = LocalDateTime.now(clock);
        final String contractNo = CONTRACT_NO_PREFIX
                + String.format("%06d", repository.nextContractNoSeq());
        final Contract contract = new Contract(null, contractNo, product.productId(),
                product.productName(), product.providerSubjectNo(), cmd.requesterNo(),
                cmd.templateNo(), cmd.templateVersionNo(), product.pricingModel(),
                product.priceAmount(), 1, ContractStatus.NEGOTIATING, null, null, null,
                null, null, null, null, cmd.requesterNo(), now, now);
        final ContractClauseVersion versionV1 = new ContractClauseVersion(null, 0L, 1,
                dealTextCipher.encrypt(prepared.parsed().values().toStorageText()), null, null,
                null, cmd.requesterNo(), now, null, null);
        final ContractActionLog logRow = new ContractActionLog(null, contractNo, 1,
                ContractAction.CREATE, cmd.requesterNo(), null, null, "1", now);
        try {
            repository.create(contract, versionV1, logRow);
        } catch (final DuplicateKeyException e) {
            log.warn("合约发起并发撞编号唯一索引(uk_contract_no): requesterNo={}, productId={}",
                    cmd.requesterNo(), cmd.productId());
            throw new ContractBizException(ContractErrorCodes.CONTRACT_CONCURRENT_MODIFICATION,
                    ContractErrorCodes.CONTRACT_CONCURRENT_MODIFICATION_MESSAGE);
        }
        return new InitiateResult(contractNo, ContractStatus.NEGOTIATING.name(), 1,
                cmd.templateNo(), cmd.templateVersionNo(), cmd.productId());
    }

    /** W5 便利入口（prepare + persist；幂等注解在调用方 ContractCommandService 原地保留）。 */
    public InitiateResult initiate(final InitiateCommand cmd) {
        return persist(prepare(cmd));
    }

    private void requireAdmitted(final String subjectNo, final String contractNo,
            final String message) {
        final SubjectAdmission admission = subjectAdmissionPort.check(subjectNo);
        if (admission == SubjectAdmission.NOT_ADMITTED) {
            repository.insertLog(new ContractActionLog(null, contractNo, null,
                    ContractAction.DENIED_ACCESS, subjectNo,
                    ContractErrorCodes.tailOf(ContractErrorCodes.ADMISSION_REQUIRED), null, null,
                    LocalDateTime.now(clock)));
            throw new ContractBizException(ContractErrorCodes.ADMISSION_REQUIRED, message);
        }
        if (admission == SubjectAdmission.UNAVAILABLE) {
            throw new ContractBizException(ContractErrorCodes.SUBJECT_SERVICE_UNAVAILABLE,
                    ContractErrorCodes.SUBJECT_SERVICE_UNAVAILABLE_MESSAGE);
        }
    }
}
