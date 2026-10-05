package com.ctds.contract.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.idempotency.Idempotent;
import com.ctds.contract.domain.CatalogProductPort;
import com.ctds.contract.domain.ClauseValues;
import com.ctds.contract.domain.Contract;
import com.ctds.contract.domain.ContractAction;
import com.ctds.contract.domain.ContractActionLog;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.ContractRepository;
import com.ctds.contract.domain.ContractStatus;
import com.ctds.contract.domain.ContractTransitions;
import com.ctds.contract.domain.ContractClauseVersion;
import com.ctds.contract.domain.ContractCanonicalizer;
import com.ctds.contract.domain.DealTextCipher;
import com.ctds.contract.domain.DidPort;
import com.ctds.contract.domain.LifecycleAction;
import com.ctds.contract.domain.PartyRole;
import com.ctds.contract.domain.SubjectAdmission;
import com.ctds.contract.domain.SubjectAdmissionPort;
import com.ctds.contract.domain.TerminationType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 合约写面应用服务（WBS-3.4.3 hifi §2.1/§5/§6：发起 / 提案反提案 / 确认 / 协商终止 /
 * 电子签署 / 拒签终止 / 合意解除 / 强制终止）。
 *
 * <p>门槛单点（行为 7 规则 3）：可见性（非参与方与不存在同码同文逐字 1008C0012 防枚举 +
 * DENIED_ACCESS 留痕）、参与方与资格（Q8-A：每次业务写动作校验操作者 ADMITTED fail-closed）、
 * 状态机（{@link ContractTransitions} 全转移表，1008C0013 + 留痕）一律在应用服务承载——
 * 注解层拒绝发生在控制器之前无法写规格要求的留痕（沿 3.4.2 hifi V1.1 §2.1 口径）。</p>
 *
 * <p>事务口径（hifi §5）：did 调用在事务外先行（W9 解析 → 归属/状态校验 → 代签）；多写事务
 * 边界在仓储（FOR UPDATE 复判 + 条件更新恰一次转移）；拒绝留痕独立写入（主链回滚不影响）。
 * 并发兜底：撞 uk_contract_no / uk_contract_version 按约束名转译 1008C0019（换驱动回归
 * 清单 hifi §10-2）。</p>
 *
 * <p>L3 承载（hifi §6）：条款值 / 变更明细 / 规范化原文 / 签名值经 {@link DealTextCipher}
 * （common-crypto Sm4Service 唯一入口）密文落库；内容哈希 = 规范化 JSON 的 SM3 摘要
 * （{@link ContractCanonicalizer}，不可逆摘要明文落库）。</p>
 */
@Service
public class ContractCommandService {

    private static final Logger log = LoggerFactory.getLogger(ContractCommandService.class);
    /** 密文回读 JSON 解析（存储形态已校验合法；Mapper 线程安全共享）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper CIPHER_JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    /** 合约编号前缀（Q1-A：CO + 6 位全局序号）。 */
    private static final String CONTRACT_NO_PREFIX = "CO";
    /** 演示期平台运营方角色名（yml ctds.auth.permissions.admin 映射；模板服务同款先例）。 */
    private static final String ADMIN_ROLE = "admin";
    /** 强制终止理由长度上限（hifi §2.3 W12：必填 1~512）。 */
    private static final int FORCE_TERMINATE_REASON_MAX = 512;
    /** 产品在架状态硬门槛（行为 2 规则 2；状态原值来自 catalog 内部端点）。 */
    private static final String STATUS_LISTED = "LISTED";
    /** DID 有效状态（签署前解析校验——hifi §6.4 ②）。 */
    private static final String DID_STATUS_ACTIVE = "ACTIVE";

    private final ContractRepository repository;
    private final TemplateQueryService templateQueryService;
    private final SubjectAdmissionPort subjectAdmissionPort;
    private final CatalogProductPort catalogProductPort;
    private final DidPort didPort;
    private final DealTextCipher dealTextCipher;
    private final Sm3Service sm3Service;
    private final Clock clock;

    public ContractCommandService(final ContractRepository repository,
            final TemplateQueryService templateQueryService,
            final SubjectAdmissionPort subjectAdmissionPort,
            final CatalogProductPort catalogProductPort, final DidPort didPort,
            final DealTextCipher dealTextCipher, final Sm3Service sm3Service, final Clock clock) {
        this.repository = repository;
        this.templateQueryService = templateQueryService;
        this.subjectAdmissionPort = subjectAdmissionPort;
        this.catalogProductPort = catalogProductPort;
        this.didPort = didPort;
        this.dealTextCipher = dealTextCipher;
        this.sm3Service = sm3Service;
        this.clock = clock;
    }

    // ==== W5 发起（行为 2 规则 1/2/3；门槛链顺序固定 hifi §5.1）====

    /**
     * 发起合约（需求方）：资格 → 产品事实（LISTED 硬门槛）→ 不同主体 → 模板 QV1/QV2
     * （承接-1：停用模板发起被拒 C0017）→ 条款值校验 → 取号 + 快照锁定三写。
     * 幂等键 = requesterNo + productId + templateNo + templateVersionNo + requestFingerprint
     * （同键重放返回首次结果、合约数不变——剧本 S1-7）。
     */
    @Idempotent(key = "'DEAL:' + #cmd.requesterNo + ':' + #cmd.productId + ':' + #cmd.templateNo "
            + "+ ':' + #cmd.templateVersionNo + ':' + #cmd.requestFingerprint")
    public InitiateResult initiate(final InitiateCommand cmd) {
        requireAdmitted(cmd.requesterNo(), ContractErrorCodes.INITIATE_ADMISSION_REQUIRED_MESSAGE);
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
        final List<String> policyViolations = new java.util.ArrayList<>(parsed.strategyViolations());
        policyViolations.addAll(parsed.values().strategy().basicValueViolations());
        if (!policyViolations.isEmpty()) {
            log.info("策略条款不符合使用控制约定: productId={}, violations={}", cmd.productId(),
                    policyViolations);
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
        // ⑥ 锁定快照写库（取号 + contract + 版本 V1 + 留痕 CREATE 同事务）
        final LocalDateTime now = LocalDateTime.now(clock);
        final String contractNo = CONTRACT_NO_PREFIX
                + String.format("%06d", repository.nextContractNoSeq());
        final Contract contract = new Contract(null, contractNo, product.productId(),
                product.productName(), product.providerSubjectNo(), cmd.requesterNo(),
                cmd.templateNo(), cmd.templateVersionNo(), product.pricingModel(),
                product.priceAmount(), 1, ContractStatus.NEGOTIATING, null, null, null,
                null, null, null, null, cmd.requesterNo(), now, now);
        final ContractClauseVersion versionV1 = new ContractClauseVersion(null, 0L, 1,
                dealTextCipher.encrypt(parsed.values().toStorageText()), null, null, null,
                cmd.requesterNo(), now, null, null);
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

    // ==== W6 提案/反提案（行为 2 规则 4：交替提案 + 版本 +1 + 变更明细）====

    /**
     * 提案/反提案：参与方 → 资格 → 状态门槛 → 交替门槛（当前版本提案方不得连续提案）→
     * 条款值校验 → 新版本行 + 版本指针前移 + 留痕（from = Vn，to = Vn+1）。
     */
    public ProposeResult propose(final String contractNo, final JsonNode clauseValues,
            final String operatorNo) {
        final Contract contract = requireVisibleForWrite(contractNo, operatorNo);
        requireAdmitted(operatorNo, ContractErrorCodes.DEAL_ADMISSION_REQUIRED_MESSAGE);
        final ContractClauseVersion current = repository
                .findVersion(contract.id(), contract.currentClauseVersion()).orElseThrow();
        if (!requireState(contract, LifecycleAction.PROPOSE, operatorNo)) {
            throw stateForbidden(operatorNo, contract, contract.currentClauseVersion(),
                    ContractAction.PROPOSE);
        }
        // 交替提案门槛（Q3-A：当前版本提案方不得连续提案，须待对方反提案）
        if (current.proposedBy().equals(operatorNo)) {
            throw stateForbidden(operatorNo, contract, contract.currentClauseVersion(),
                    ContractAction.PROPOSE);
        }
        final ClauseValues newValues = validateClauseValues(contract, clauseValues);
        final ClauseValues previousValues = storedClauseValues(current.clauseValuesCipher());
        final List<ClauseValues.Change> changes =
                ClauseValues.diffAgainst(previousValues, newValues);
        final LocalDateTime now = LocalDateTime.now(clock);
        final int nextVersionNo = contract.currentClauseVersion() + 1;
        final ContractClauseVersion newVersion = new ContractClauseVersion(null, contract.id(),
                nextVersionNo, dealTextCipher.encrypt(newValues.toStorageText()),
                changes.isEmpty() ? null
                        : dealTextCipher.encrypt(ClauseValues.changesJson(changes).toString()),
                null, null, operatorNo, now, null, null);
        final ContractActionLog logRow = new ContractActionLog(null, contractNo,
                contract.currentClauseVersion(), ContractAction.PROPOSE, operatorNo, null,
                String.valueOf(contract.currentClauseVersion()), String.valueOf(nextVersionNo),
                now);
        try {
            repository.propose(contract.id(), newVersion, logRow);
        } catch (final DuplicateKeyException e) {
            log.warn("合约提案并发撞版本唯一索引(uk_contract_version): contractNo={}, 目标版本{}",
                    contractNo, nextVersionNo);
            throw new ContractBizException(ContractErrorCodes.CONTRACT_CONCURRENT_MODIFICATION,
                    ContractErrorCodes.CONTRACT_CONCURRENT_MODIFICATION_MESSAGE);
        }
        return new ProposeResult(contractNo, nextVersionNo, contract.currentClauseVersion());
    }

    // ==== W7 确认（行为 2 规则 4：逐方确认、双方齐 → 锁定转待签署；行为 4 规则 2 策略门槛）====

    /** 确认当前条款版本：每方一次（重复确认 C0013 + 留痕）；双方齐 → 规范化原文与哈希固化。 */
    public ConfirmResult confirm(final String contractNo, final String operatorNo) {
        final Contract contract = requireVisibleForWrite(contractNo, operatorNo);
        requireAdmitted(operatorNo, ContractErrorCodes.DEAL_ADMISSION_REQUIRED_MESSAGE);
        if (!requireState(contract, LifecycleAction.CONFIRM, operatorNo)) {
            throw stateForbidden(operatorNo, contract, contract.currentClauseVersion(),
                    ContractAction.CONFIRM);
        }
        final PartyRole role = contract.roleOf(operatorNo);
        final ContractClauseVersion current = repository
                .findVersion(contract.id(), contract.currentClauseVersion()).orElseThrow();
        if (current.confirmedAt(role) != null) {
            throw stateForbidden(operatorNo, contract, contract.currentClauseVersion(),
                    ContractAction.CONFIRM);
        }
        // 确认锁定门槛（行为 4 规则 2）：至少一项策略要素启用或显式"无使用限制"声明
        final ClauseValues values = storedClauseValues(current.clauseValuesCipher());
        if (!values.strategy().hasAnyRestrictionOrDeclared()) {
            throw new ContractBizException(ContractErrorCodes.POLICY_CLAUSE_INVALID,
                    ContractErrorCodes.POLICY_CLAUSE_INVALID_MESSAGE);
        }
        // 规范化原文 + 内容哈希（确定性序列化——双方计算一致；T5 断言解密原文重算哈希一致）
        final String canonicalJson = ContractCanonicalizer.canonicalJson(contract, values);
        final String contentHash = ContractCanonicalizer.contentHash(canonicalJson, sm3Service);
        final LocalDateTime now = LocalDateTime.now(clock);
        final ContractActionLog logRow = new ContractActionLog(null, contractNo,
                contract.currentClauseVersion(), ContractAction.CONFIRM, operatorNo, null, null,
                String.valueOf(contract.currentClauseVersion()), now);
        final ContractRepository.ConfirmOutcome outcome = repository.confirm(
                new ContractRepository.ConfirmCommand(contract.id(), contract.currentClauseVersion(),
                        role, operatorNo, now, dealTextCipher.encrypt(canonicalJson), contentHash,
                        logRow));
        if (outcome == ContractRepository.ConfirmOutcome.DUPLICATE_CONFIRM
                || outcome == ContractRepository.ConfirmOutcome.STATE_CONFLICT) {
            // 并发复查失败（对方确认齐后锁定/协商终止竞态）——同状态门槛口径拒绝留痕
            throw stateForbidden(operatorNo, contract, contract.currentClauseVersion(),
                    ContractAction.CONFIRM);
        }
        final Contract fresh = repository.findByNo(contractNo).orElseThrow();
        final ContractClauseVersion freshVersion = repository
                .findVersion(contract.id(), contract.currentClauseVersion()).orElseThrow();
        return new ConfirmResult(contractNo, contract.currentClauseVersion(),
                freshVersion.confirmedProviderAt() != null,
                freshVersion.confirmedRequesterAt() != null, fresh.status().name());
    }

    // ==== W8 协商终止 / W10 拒签终止（Q4-A：未双签生效前任一方可拒签——含已签方撤回）====

    /** 协商终止（NEGOTIATING 态任一方；终态不可逆）。 */
    public TerminateResult terminateNegotiation(final String contractNo, final String operatorNo) {
        return terminate(contractNo, operatorNo, Set.of(ContractStatus.NEGOTIATING),
                TerminationType.NEGOTIATION_TERMINATED, ContractAction.TERMINATE_NEGOTIATION, null);
    }

    /** 拒签终止（未双签生效前——待签署/部分签署两态；生效后拒绝 C0013——剧本 S2-4/S2-5）。 */
    public TerminateResult refuseSignature(final String contractNo, final String operatorNo) {
        return terminate(contractNo, operatorNo,
                Set.of(ContractStatus.PENDING_SIGNATURE, ContractStatus.PARTIALLY_SIGNED),
                TerminationType.SIGNATURE_REFUSED, ContractAction.REFUSE_SIGN, null);
    }

    private TerminateResult terminate(final String contractNo, final String operatorNo,
            final Set<ContractStatus> fromStates, final TerminationType type,
            final ContractAction action, final String reason) {
        // 参与方终止分支（W8/W10）走参与方可见性；强制终止（W12）由 forceTerminate 先行
        // 以治理路径定位（admin 非参与方——不适用参与方门槛，hifi §2.3）
        final Contract contract = action == ContractAction.FORCE_TERMINATE
                ? requireExistingForGovernance(contractNo, operatorNo)
                : requireVisibleForWrite(contractNo, operatorNo);
        requireAdmitted(operatorNo, ContractErrorCodes.DEAL_ADMISSION_REQUIRED_MESSAGE);
        final LifecycleAction lifecycleAction = action == ContractAction.TERMINATE_NEGOTIATION
                ? LifecycleAction.NEGOTIATION_TERMINATE
                : action == ContractAction.REFUSE_SIGN
                        ? LifecycleAction.REFUSE_SIGN : LifecycleAction.FORCE_TERMINATE;
        if (!requireState(contract, lifecycleAction, operatorNo)) {
            throw stateForbidden(operatorNo, contract, contract.currentClauseVersion(), action);
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        final ContractActionLog logRow = new ContractActionLog(null, contractNo,
                contract.currentClauseVersion(), action, operatorNo, null, contract.status().name(),
                ContractStatus.TERMINATED.name(), now);
        final ContractRepository.TerminateOutcome outcome = repository.terminate(
                new ContractRepository.TerminateCommand(contract.id(), contractNo, fromStates,
                        type, reason, operatorNo, now, logRow));
        if (outcome == ContractRepository.TerminateOutcome.STATE_CONFLICT) {
            throw stateForbidden(operatorNo, contract, contract.currentClauseVersion(), action);
        }
        return new TerminateResult(contractNo, ContractStatus.TERMINATED.name(), type.name());
    }

    // ==== W9 电子签署（行为 3 规则 1/2/5：DID+SM2 签哈希、双签生效、存证事件）====

    /**
     * 电子签署：参与方 → 资格 → 状态门槛 → 本角色未签 → **事务外** DID 解析（归属 + 状态）
     * → 演示签名入口代签（内容 = 锁定版本内容哈希）→ 事务内条件转移 + 签名密文落库 +
     * （后签）存证事件 + 生效时间 = 本签时间。
     */
    public SignResult sign(final String contractNo, final String did, final String operatorNo) {
        final Contract contract = requireVisibleForWrite(contractNo, operatorNo);
        requireAdmitted(operatorNo, ContractErrorCodes.DEAL_ADMISSION_REQUIRED_MESSAGE);
        final PartyRole role = contract.roleOf(operatorNo);
        if (!requireState(contract, LifecycleAction.SIGN, operatorNo)) {
            throw stateForbidden(operatorNo, contract, contract.currentClauseVersion(),
                    ContractAction.SIGN);
        }
        if (repository.findSignature(contract.id(), role).isPresent()) {
            throw stateForbidden(operatorNo, contract, contract.currentClauseVersion(),
                    ContractAction.SIGN);
        }
        final ContractClauseVersion lockedVersion = repository
                .findVersion(contract.id(), contract.currentClauseVersion()).orElseThrow();
        if (lockedVersion.contentHash() == null) {
            // 未锁定版本无签名输入（状态门槛已保证，防御腿——恰因终态/并发竞态出现）
            throw stateForbidden(operatorNo, contract, contract.currentClauseVersion(),
                    ContractAction.SIGN);
        }
        // ② DID 解析：未登记 / 非 ACTIVE / 非签署方归属 → 1008C0018；不可达 → 1008S0002
        final DidPort.DidBinding binding = didPort.resolve(did);
        if (binding.state() == DidPort.DidBinding.State.UNAVAILABLE) {
            throw new ContractBizException(ContractErrorCodes.DID_SERVICE_UNAVAILABLE,
                    ContractErrorCodes.DID_SERVICE_UNAVAILABLE_MESSAGE);
        }
        if (binding.state() == DidPort.DidBinding.State.NOT_REGISTERED
                || !DID_STATUS_ACTIVE.equals(binding.status())
                || !operatorNo.equals(binding.controller())) {
            throw new ContractBizException(ContractErrorCodes.SIGNATURE_IDENTITY_UNAVAILABLE,
                    ContractErrorCodes.SIGNATURE_IDENTITY_UNAVAILABLE_MESSAGE);
        }
        // ③ 代签（演示签名入口，服务身份头；入口关闭/不可达 → 1008S0002 不冒充）
        final DidPort.DidSignResult signResult = didPort.sign(did, lockedVersion.contentHash());
        if (signResult.state() == DidPort.DidSignResult.State.UNAVAILABLE) {
            throw new ContractBizException(ContractErrorCodes.DID_SERVICE_UNAVAILABLE,
                    ContractErrorCodes.DID_SERVICE_UNAVAILABLE_MESSAGE);
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        final ContractActionLog signLog = new ContractActionLog(null, contractNo,
                contract.currentClauseVersion(), ContractAction.SIGN, operatorNo, null,
                contract.status().name(), null, now);
        final ContractActionLog attestLog = new ContractActionLog(null, contractNo,
                contract.currentClauseVersion(), ContractAction.ATTEST, operatorNo, null, null,
                ContractStatus.EFFECTIVE.name(), now);
        final ContractRepository.SignOutcome outcome = repository.sign(
                new ContractRepository.SignCommand(contract.id(), role, operatorNo, did,
                        lockedVersion.contentHash(), dealTextCipher.encrypt(signResult.signatureBase64()),
                        now, signLog, attestLog));
        if (outcome == ContractRepository.SignOutcome.DUPLICATE_SIGN
                || outcome == ContractRepository.SignOutcome.STATE_CONFLICT) {
            throw stateForbidden(operatorNo, contract, contract.currentClauseVersion(),
                    ContractAction.SIGN);
        }
        final Contract fresh = repository.findByNo(contractNo).orElseThrow();
        return new SignResult(contractNo, role.name(), fresh.status().name(), fresh.effectiveAt());
    }

    // ==== W11 合意解除确认（仅 EFFECTIVE；每方一次；双方齐 → 已完结）====

    public ReleaseResult releaseConsent(final String contractNo, final String operatorNo) {
        final Contract contract = requireVisibleForWrite(contractNo, operatorNo);
        requireAdmitted(operatorNo, ContractErrorCodes.DEAL_ADMISSION_REQUIRED_MESSAGE);
        final PartyRole role = contract.roleOf(operatorNo);
        final LocalDateTime now = LocalDateTime.now(clock);
        final ContractActionLog logRow = new ContractActionLog(null, contractNo,
                contract.currentClauseVersion(), ContractAction.RELEASE_CONSENT, operatorNo, null,
                contract.status().name(), null, now);
        final ContractRepository.ReleaseOutcome outcome = repository.releaseConsent(
                new ContractRepository.ReleaseCommand(contract.id(), contractNo, role, operatorNo,
                        now, logRow));
        if (outcome == ContractRepository.ReleaseOutcome.DUPLICATE_CONSENT
                || outcome == ContractRepository.ReleaseOutcome.STATE_CONFLICT) {
            throw stateForbidden(operatorNo, contract, contract.currentClauseVersion(),
                    ContractAction.RELEASE_CONSENT);
        }
        final Contract fresh = repository.findByNo(contractNo).orElseThrow();
        return new ReleaseResult(contractNo, fresh.status().name(),
                fresh.releaseConsentProviderAt() != null, fresh.releaseConsentRequesterAt() != null);
    }

    // ==== W12 强制终止（行为 6 规则 4：仅运营方 + 理由必填；应用层判定为注解之外越权腿）====

    /** 强制终止（EFFECTIVE 态；理由必填 1~512；留痕含操作者，理由落 contract 列）。 */
    public TerminateResult forceTerminate(final String contractNo, final String operatorNo,
            final String reason) {
        // 应用层 admin 判定（hifi §2.3 权限注：contract.governance 之外的越权腿——C0011 + 留痕）
        if (!AuthContext.roles().contains(ADMIN_ROLE)) {
            repository.insertLog(new ContractActionLog(null, contractNo, null,
                    ContractAction.DENIED_ACCESS, operatorNo,
                    ContractErrorCodes.tailOf(ContractErrorCodes.CONTRACT_GOVERNANCE_FORBIDDEN),
                    null, null, LocalDateTime.now(clock)));
            throw new ContractBizException(ContractErrorCodes.CONTRACT_GOVERNANCE_FORBIDDEN,
                    ContractErrorCodes.CONTRACT_GOVERNANCE_FORBIDDEN_MESSAGE);
        }
        if (reason == null || reason.isBlank() || reason.length() > FORCE_TERMINATE_REASON_MAX) {
            throw new ContractBizException(ContractErrorCodes.CONTRACT_PARAM_INVALID,
                    ContractErrorCodes.TEMPLATE_PARAM_INVALID_MESSAGE);
        }
        return terminate(contractNo, operatorNo, Set.of(ContractStatus.EFFECTIVE),
                TerminationType.GOVERNANCE_FORCE_TERMINATED, ContractAction.FORCE_TERMINATE, reason);
    }

    // ==== 结果载体 ====

    /** W6 结果（新版本号 + 上一版版本号）。 */
    public record ProposeResult(String contractNo, int clauseVersionNo, int previousVersionNo) {
    }

    /** W7 结果（逐方确认状态 + 状态机现态）。 */
    public record ConfirmResult(String contractNo, int clauseVersionNo, boolean providerConfirmed,
            boolean requesterConfirmed, String status) {
    }

    /** W8/W10/W12 结果（终态 + 终止类型）。 */
    public record TerminateResult(String contractNo, String status, String terminationType) {
    }

    /** W9 结果（本方角色 + 状态机现态 + 生效时间〔后签才非空〕）。 */
    public record SignResult(String contractNo, String partyRole, String status,
            LocalDateTime effectiveAt) {
    }

    /** W11 结果（逐方解除确认状态 + 状态机现态）。 */
    public record ReleaseResult(String contractNo, String status, boolean providerConsented,
            boolean requesterConsented) {
    }

    // ==== 内部：门槛单点 ====

    /** 资格三态（Q8-A fail-closed；统一文案按语境分——沿 C0003 同码多文案先例）。 */
    private void requireAdmitted(final String subjectNo, final String message) {
        final SubjectAdmission admission = subjectAdmissionPort.check(subjectNo);
        if (admission == SubjectAdmission.NOT_ADMITTED) {
            throw new ContractBizException(ContractErrorCodes.ADMISSION_REQUIRED, message);
        }
        if (admission == SubjectAdmission.UNAVAILABLE) {
            throw new ContractBizException(ContractErrorCodes.SUBJECT_SERVICE_UNAVAILABLE,
                    ContractErrorCodes.SUBJECT_SERVICE_UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * 可见性单点（行为 7 规则 1）：非参与方与不存在同码同文逐字（1008C0012 防枚举）+
     * DENIED_ACCESS 留痕（不存在场景 contract_no 为 NULL——留痕表列注释口径）。
     */
    private Contract requireVisibleForWrite(final String contractNo, final String operatorNo) {
        final Contract contract = repository.findByNo(contractNo).orElse(null);
        if (contract == null || contract.roleOf(operatorNo) == null) {
            repository.insertLog(new ContractActionLog(null,
                    contract == null ? null : contractNo, null, ContractAction.DENIED_ACCESS,
                    operatorNo,
                    ContractErrorCodes.tailOf(ContractErrorCodes.CONTRACT_NOT_VISIBLE),
                    null, null, LocalDateTime.now(clock)));
            throw new ContractBizException(ContractErrorCodes.CONTRACT_NOT_VISIBLE,
                    ContractErrorCodes.CONTRACT_NOT_VISIBLE_MESSAGE);
        }
        return contract;
    }

    /** 状态机门槛复查（纯判定，不落留痕——拒绝留痕由 {@link #stateForbidden} 单点写入）。 */
    private boolean requireState(final Contract contract, final LifecycleAction action,
            final String operatorNo) {
        if (ContractTransitions.allowed(action, contract.status())) {
            return true;
        }
        log.info("合约状态不允许该操作: contractNo={}, action={}, status={}, operator={}",
                contract.contractNo(), action, contract.status(), operatorNo);
        return false;
    }

    /** 状态门槛拒绝（1008C0013 + 留痕——action 语义保留在留痕行；拒绝留痕单点）。 */
    private ContractBizException stateForbidden(final String operatorNo, final Contract contract,
            final Integer versionNo, final ContractAction action) {
        repository.insertLog(new ContractActionLog(null, contract.contractNo(), versionNo, action,
                operatorNo, ContractErrorCodes.tailOf(ContractErrorCodes.CONTRACT_STATE_FORBIDDEN),
                contract.status().name(), null, LocalDateTime.now(clock)));
        return new ContractBizException(ContractErrorCodes.CONTRACT_STATE_FORBIDDEN,
                ContractErrorCodes.CONTRACT_STATE_FORBIDDEN_MESSAGE);
    }

    /** 条款值校验（框架 = 合约锁定模板版本——承接-2 快照稳定性；C0014/C0015 明细入日志）。 */
    private ClauseValues validateClauseValues(final Contract contract, final JsonNode clauseValues) {
        final ClauseValues.Parsed parsed = ClauseValues.parse(clauseValues);
        if (parsed.hasSlotViolations()) {
            log.info("条款值与模板框架不符: contractNo={}, violations={}", contract.contractNo(),
                    parsed.slotViolations());
            throw new ContractBizException(ContractErrorCodes.CLAUSE_VALUES_INVALID,
                    ContractErrorCodes.CLAUSE_VALUES_INVALID_MESSAGE);
        }
        final List<String> policyViolations = new java.util.ArrayList<>(parsed.strategyViolations());
        policyViolations.addAll(parsed.values().strategy().basicValueViolations());
        if (!policyViolations.isEmpty()) {
            log.info("策略条款不符合使用控制约定: contractNo={}, violations={}", contract.contractNo(),
                    policyViolations);
            throw new ContractBizException(ContractErrorCodes.POLICY_CLAUSE_INVALID,
                    ContractErrorCodes.POLICY_CLAUSE_INVALID_MESSAGE);
        }
        final var frameworkVersion = templateQueryService
                .loadFramework(contract.templateNo(), contract.templateVersionNo())
                .orElseThrow(() -> new ContractBizException(
                        ContractErrorCodes.TEMPLATE_NOT_AVAILABLE_FOR_INITIATION,
                        ContractErrorCodes.TEMPLATE_NOT_AVAILABLE_FOR_INITIATION_MESSAGE));
        final List<String> frameworkViolations =
                parsed.values().frameworkViolations(frameworkVersion.clauseFrameworkJson());
        if (!frameworkViolations.isEmpty()) {
            log.info("条款值与模板框架不符: contractNo={}, violations={}", contract.contractNo(),
                    frameworkViolations);
            throw new ContractBizException(ContractErrorCodes.CLAUSE_VALUES_INVALID,
                    ContractErrorCodes.CLAUSE_VALUES_INVALID_MESSAGE);
        }
        return parsed.values();
    }

    /** 密文回读（存储形态已校验合法——容错读）。 */
    private ClauseValues storedClauseValues(final byte[] cipher) {
        return ClauseValues.fromStored(parseJson(dealTextCipher.decrypt(cipher)));
    }

    /**
     * 治理路径定位（W12 强制终止；admin 非参与方——不适用参与方门槛）：仅存在性判定，
     * 不存在 → 1008C0012 同形 + DENIED_ACCESS 留痕（contract_no 为 NULL——留痕表列注释口径）。
     */
    private Contract requireExistingForGovernance(final String contractNo, final String operatorNo) {
        final Contract contract = repository.findByNo(contractNo).orElse(null);
        if (contract == null) {
            repository.insertLog(new ContractActionLog(null, null, null,
                    ContractAction.DENIED_ACCESS, operatorNo,
                    ContractErrorCodes.tailOf(ContractErrorCodes.CONTRACT_NOT_VISIBLE),
                    null, null, LocalDateTime.now(clock)));
            throw new ContractBizException(ContractErrorCodes.CONTRACT_NOT_VISIBLE,
                    ContractErrorCodes.CONTRACT_NOT_VISIBLE_MESSAGE);
        }
        return contract;
    }

    private JsonNode parseJson(final String text) {
        try {
            return CIPHER_JSON_MAPPER.readTree(text);
        } catch (final com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalStateException("合约密文回读解析失败（存储层已校验合法）", e);
        }
    }
}
