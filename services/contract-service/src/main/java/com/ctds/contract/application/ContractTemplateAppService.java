package com.ctds.contract.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.errorcode.ErrorCode;
import com.ctds.common.idempotency.Idempotent;
import com.ctds.contract.domain.ClauseFramework;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.ContractTemplate;
import com.ctds.contract.domain.ContractTemplateRepository;
import com.ctds.contract.domain.SubjectAdmission;
import com.ctds.contract.domain.SubjectAdmissionPort;
import com.ctds.contract.domain.TemplateAction;
import com.ctds.contract.domain.TemplateActionLog;
import com.ctds.contract.domain.TemplateNameNormalizer;
import com.ctds.contract.domain.TemplateStatus;
import com.ctds.contract.domain.TemplateType;
import com.ctds.contract.domain.TemplateVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 模板写面应用服务（新增/修订/启停 + 留痕；WBS-3.4.2 hifi §2.1/§6）。
 *
 * <p>维护权唯一（行为 1 规则 1）在应用服务单点 {@link #requireMaintainer} 承载：资格三态
 * （未入驻 → 1008C0003 统一文案防枚举；UNAVAILABLE → 1008S0001 不冒充）→ admin 档角色判定
 * （非 admin → 1008C0002 + DENIED_MANAGE 拒绝留痕）——拒绝留痕须落库，而注解层拒绝发生在
 * 控制器之前无法留痕，故写面注解仅用 {@code contract.template.read} 作功能第一道门槛
 * （hifi V1.1 §2.1 补正口径）。</p>
 *
 * <p>事务口径（hifi §6）：新增 = 取号 + 三写（主表/版本 V1/留痕）；修订 = 三写（版本 Vn+1/
 * 主表指针/留痕），并发撞 uk_template_version → 1008C0009；启停 = 两写（主表状态/留痕），
 * 同态重复 → 1008C0007 + DENIED 留痕；拒绝留痕独立写入（主链回滚不影响留痕，沿 catalog 先例）。
 * 版本行不可变：本服务无任何改写版本行的路径。</p>
 */
@Service
public class ContractTemplateAppService {

    private static final Logger log = LoggerFactory.getLogger(ContractTemplateAppService.class);
    /** 模板编号前缀（Q3-A：CT + 6 位全局序号；预置种子占 CT000001~CT000003）。 */
    private static final String TEMPLATE_NO_PREFIX = "CT";
    /** 演示期平台运营方角色名（yml ctds.auth.permissions.admin 映射；3.3.6 目录域角色头先例）。 */
    private static final String ADMIN_ROLE = "admin";

    private final ContractTemplateRepository repository;
    private final SubjectAdmissionPort subjectAdmissionPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ContractTemplateAppService(final ContractTemplateRepository repository,
            final SubjectAdmissionPort subjectAdmissionPort, final ObjectMapper objectMapper,
            final Clock clock) {
        this.repository = repository;
        this.subjectAdmissionPort = subjectAdmissionPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 新增模板（W1，行为 1 规则 1/2/6/7）：维护权 → 名称/类型要素（1008C0008）→ 条款框架
     * 校验（1008C0004 逐槽位）→ 同类型同名判重（1008C0005，唯一索引兜底并发）→ 取号 + 三写。
     * 幂等键 = 操作者 + 类型 + 名称（hifi §2.1；服务端派生，同键重放返回首次结果）。
     * 注：幂等切面 SpEL 上下文为只读数据绑定（common-idempotency SpelKeyResolver——禁方法
     * 调用/T() 引用），键成分用原始名；归一化判重由应用层预查 + uk_type_norm_name 唯一索引
     * 兜底承担（口径单点在 DB 生成列，hifi V1.1 §2.1 登记）。
     */
    @Idempotent(key = "'CREATE:' + #cmd.operatorNo + ':' + #cmd.type + ':' + #cmd.name")
    public ContractTemplate create(final CreateTemplateCommand cmd) {
        requireMaintainer(cmd.operatorNo(), null);
        requireName(cmd.name());
        requireFramework(cmd.type(), cmd.clauseFrameworkJson());
        if (repository.existsByTypeAndNormalizedName(cmd.type(), TemplateNameNormalizer.normalize(cmd.name()))) {
            throw new ContractBizException(ContractErrorCodes.TEMPLATE_NAME_DUPLICATED,
                    ContractErrorCodes.TEMPLATE_NAME_DUPLICATED_MESSAGE);
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        final String templateNo = TEMPLATE_NO_PREFIX + String.format("%06d", repository.nextTemplateNoSeq());
        final ContractTemplate template = new ContractTemplate(null, templateNo, cmd.name(), cmd.type(),
                1, TemplateStatus.ENABLED, cmd.operatorNo(), now, now);
        final TemplateVersion version = new TemplateVersion(null, 0L, 1, cmd.clauseFrameworkJson(),
                cmd.operatorNo(), now);
        final TemplateActionLog logRow = new TemplateActionLog(null, templateNo, 1,
                TemplateAction.CREATE, cmd.operatorNo(), null, null, "1", now);
        try {
            repository.create(template, version, logRow);
        } catch (final DuplicateKeyException e) {
            // uk_type_norm_name 并发兜底（预查与写入窗口）——转译 1008C0005，沿 catalog 先例
            log.warn("模板新增并发撞唯一索引: type={}, name={}", cmd.type(), cmd.name());
            throw new ContractBizException(ContractErrorCodes.TEMPLATE_NAME_DUPLICATED,
                    ContractErrorCodes.TEMPLATE_NAME_DUPLICATED_MESSAGE);
        }
        return repository.findByNo(templateNo).orElseThrow();
    }

    /**
     * 修订模板（W2，行为 1 规则 3/7）：维护权 → 模板存在（1008C0006）→ 框架校验 → 新版本
     * Vn+1 三写（版本行新增 + 指针前移 + 留痕 from→to）。停用态可修订（出新版本不改变停用态，
     * hifi §2.1 注——最小限制口径）；并发撞 uk_template_version → 1008C0009。
     * 幂等键 = 操作者 + 模板号 + 框架稳定哈希（同内容重放返回首次新版本）。
     */
    @Idempotent(key = "'REVISE:' + #cmd.operatorNo + ':' + #cmd.templateNo + ':' + #cmd.frameworkHash")
    public TemplateVersion revise(final ReviseTemplateCommand cmd) {
        requireMaintainer(cmd.operatorNo(), cmd.templateNo());
        final ContractTemplate template = requireTemplate(cmd.templateNo());
        requireFramework(template.type(), cmd.clauseFrameworkJson());
        final int nextVersion = template.currentVersion() + 1;
        final LocalDateTime now = LocalDateTime.now(clock);
        final TemplateVersion newVersion = new TemplateVersion(null, template.id(), nextVersion,
                cmd.clauseFrameworkJson(), cmd.operatorNo(), now);
        final TemplateActionLog logRow = new TemplateActionLog(null, template.templateNo(),
                nextVersion, TemplateAction.REVISE, cmd.operatorNo(), null,
                String.valueOf(template.currentVersion()), String.valueOf(nextVersion), now);
        try {
            repository.revise(newVersion, logRow);
        } catch (final DuplicateKeyException e) {
            // uk_template_version 并发兜底（两操作者同时修订）——转译 1008C0009
            log.warn("模板修订并发撞版本唯一索引: templateNo={}, 目标版本{}", template.templateNo(), nextVersion);
            throw new ContractBizException(ContractErrorCodes.TEMPLATE_CONCURRENT_MODIFICATION,
                    ContractErrorCodes.TEMPLATE_CONCURRENT_MODIFICATION_MESSAGE);
        }
        return repository.findVersion(template.templateNo(), nextVersion).orElseThrow();
    }

    /** 停用模板（W3，行为 1 规则 4）：停用后退出浏览 + 不可新发起，不影响既有（快照归 3.4.3）。 */
    public ContractTemplate disable(final String operatorNo, final String templateNo) {
        return changeStatus(operatorNo, templateNo, TemplateStatus.DISABLED);
    }

    /** 启用模板（W4，剧本 C-4.1 S2-6）：恢复可浏览、可发起。 */
    public ContractTemplate enable(final String operatorNo, final String templateNo) {
        return changeStatus(operatorNo, templateNo, TemplateStatus.ENABLED);
    }

    // ==== 内部：门槛与要素 ====

    /** 维护权门槛（行为 1 规则 1，应用服务单点）：资格三态 → admin 档判定（拒绝留痕联动）。 */
    private void requireMaintainer(final String operatorNo, final String templateNo) {
        final SubjectAdmission admission = subjectAdmissionPort.check(operatorNo);
        if (admission == SubjectAdmission.NOT_ADMITTED) {
            insertDenied(operatorNo, templateNo, ContractErrorCodes.ADMISSION_REQUIRED);
            throw new ContractBizException(ContractErrorCodes.ADMISSION_REQUIRED,
                    ContractErrorCodes.MAINTAIN_ADMISSION_REQUIRED_MESSAGE);
        }
        if (admission == SubjectAdmission.UNAVAILABLE) {
            throw new ContractBizException(ContractErrorCodes.SUBJECT_SERVICE_UNAVAILABLE,
                    ContractErrorCodes.SUBJECT_SERVICE_UNAVAILABLE_MESSAGE);
        }
        if (!AuthContext.roles().contains(ADMIN_ROLE)) {
            insertDenied(operatorNo, templateNo, ContractErrorCodes.TEMPLATE_FORBIDDEN);
            throw new ContractBizException(ContractErrorCodes.TEMPLATE_FORBIDDEN,
                    ContractErrorCodes.TEMPLATE_FORBIDDEN_MESSAGE);
        }
    }

    /** 名称要素校验（1008C0008 逐字段：缺失/超长——hifi §3）。 */
    private void requireName(final String name) {
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new ContractBizException(ContractErrorCodes.TEMPLATE_PARAM_INVALID,
                    ContractErrorCodes.TEMPLATE_PARAM_INVALID_MESSAGE);
        }
    }

    /** 条款框架校验（1008C0004，逐槽位明细入日志不入响应文案——文案为服务端常量；T9）。 */
    private void requireFramework(final TemplateType type, final String frameworkJson) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(frameworkJson == null ? "" : frameworkJson);
        } catch (final com.fasterxml.jackson.core.JacksonException e) {
            throw new ContractBizException(ContractErrorCodes.CLAUSE_FRAMEWORK_INVALID,
                    ContractErrorCodes.CLAUSE_FRAMEWORK_INVALID_MESSAGE);
        }
        final List<ClauseFramework.SlotViolation> violations = ClauseFramework.validate(type, root);
        if (!violations.isEmpty()) {
            log.info("条款框架校验未通过: type={}, violations={}", type,
                    violations.stream().map(v -> v.key() + ":" + v.reason()).toList());
            throw new ContractBizException(ContractErrorCodes.CLAUSE_FRAMEWORK_INVALID,
                    ContractErrorCodes.CLAUSE_FRAMEWORK_INVALID_MESSAGE);
        }
    }

    /** 模板存在性（运营面直述 1008C0006，不防枚举——hifi V1.1 §3）。 */
    private ContractTemplate requireTemplate(final String templateNo) {
        return repository.findByNo(templateNo)
                .orElseThrow(() -> new ContractBizException(ContractErrorCodes.TEMPLATE_NOT_FOUND,
                        ContractErrorCodes.TEMPLATE_NOT_FOUND_MESSAGE));
    }

    /** 启停（两写 + 同态拒绝留痕 DENIED_MANAGE/C0007；状态机门槛 hifi §6）。 */
    private ContractTemplate changeStatus(final String operatorNo, final String templateNo,
            final TemplateStatus target) {
        requireMaintainer(operatorNo, templateNo);
        final ContractTemplate template = requireTemplate(templateNo);
        if (template.status() == target) {
            insertDenied(operatorNo, templateNo, ContractErrorCodes.TEMPLATE_STATE_FORBIDDEN);
            throw new ContractBizException(ContractErrorCodes.TEMPLATE_STATE_FORBIDDEN,
                    ContractErrorCodes.TEMPLATE_STATE_FORBIDDEN_MESSAGE);
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        final TemplateActionLog logRow = new TemplateActionLog(null, templateNo,
                template.currentVersion(),
                target == TemplateStatus.ENABLED ? TemplateAction.ENABLE : TemplateAction.DISABLE,
                operatorNo, null, template.status().name(), target.name(), now);
        repository.updateStatus(template, target, logRow);
        return repository.findByNo(templateNo).orElseThrow();
    }

    /** 维护拒绝留痕（DENIED_MANAGE + 理由码尾号；模板定位前拒绝 = templateNo NULL——hifi V1.1 §4）。 */
    private void insertDenied(final String operatorNo, final String templateNo,
            final ErrorCode reason) {
        repository.insertLog(new TemplateActionLog(null,
                templateNo, null, TemplateAction.DENIED_MANAGE, operatorNo,
                ContractErrorCodes.tailOf(reason), null, null, LocalDateTime.now(clock)));
    }
}
