package com.ctds.catalog.application;

import com.ctds.catalog.domain.ActionResult;
import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.CategoryPort;
import com.ctds.catalog.domain.Dataset;
import com.ctds.catalog.domain.DatasetActionLog;
import com.ctds.catalog.domain.DatasetNameNormalizer;
import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.catalog.domain.DatasetStatus;
import com.ctds.catalog.domain.SemanticTags;
import com.ctds.catalog.domain.SpaceMembership;
import com.ctds.catalog.domain.SpaceMembershipPort;
import com.ctds.catalog.domain.SubjectAdmission;
import com.ctds.catalog.domain.SubjectAdmissionPort;
import com.ctds.catalog.domain.TagTermPort;
import com.ctds.catalog.domain.TagVocabulary;
import com.ctds.common.errorcode.ErrorCode;
import com.ctds.common.idempotency.Idempotent;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

/**
 * 资源登记服务（幂等边界；WBS-3.3.2 hifi §4.2）。独立于 DatasetCommandService 的原因：
 * 幂等键 = 空间 + 登记主体 + 归一化名（hifi §4.2 定稿），归一化与要素校验在命令服务先行，
 * 跨 Bean 调用使幂等切面经代理拦截（同类自调用会被 AOP 绕过，沿 space SpaceCreationService 先例）。
 *
 * <p>登记事务顺序（WBS-3.3.2 hifi §4.2 + WBS-3.3.3 hifi §4.1 插入 6′ + WBS-3.3.4 hifi §4.1 组内后位）：
 * 资格三态 → 空间状态门槛（NONE/CREATED/FROZEN/DISSOLVED 一律 1007C0002）→ 成员门槛（NONE →
 * 1007C0006 + DENIED 留痕）→ 重要数据拒收（1007C0003 + DENIED 留痕，代码强制/§4.5-3 硬约束）→
 * <b>语义标签词条成员校验（1007C0009，WBS-3.3.3 第 6′ 步）→ 类目成员校验（1007C0014，WBS-3.3.4
 * 组内后位——不扰动 3.3.3 已定稿的错误码优先序，同一请求双非法时词表码先行）</b>→ 名称锁定与判重
 * （1007C0001）→ 取号 + INSERT + REGISTER 留痕。</p>
 */
@Service
public class DatasetRegistrationService {

    /** 数据标识前缀（Q3-A：DS + yyyyMMdd + 6 位当日序号）。 */
    private static final String DATA_NO_PREFIX = "DS";
    /** 数据标识日期段格式（yyyyMMdd）。 */
    private static final DateTimeFormatter DATA_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 登记留痕动作码（成功）。 */
    private static final String ACTION_REGISTER = "REGISTER";
    /** 登记拒绝留痕动作码（值域 = REGISTER + DENIED_ 前缀变体，hifi §3.3）。 */
    private static final String ACTION_DENIED_REGISTER = "DENIED_REGISTER";

    private final DatasetRepository repository;
    private final SubjectAdmissionPort subjectAdmissionPort;
    private final SpaceMembershipPort spaceMembershipPort;
    private final TagTermPort tagTermPort;
    private final CategoryPort categoryPort;
    private final Clock clock;

    public DatasetRegistrationService(final DatasetRepository repository,
            final SubjectAdmissionPort subjectAdmissionPort, final SpaceMembershipPort spaceMembershipPort,
            final TagTermPort tagTermPort, final CategoryPort categoryPort, final Clock clock) {
        this.repository = repository;
        this.subjectAdmissionPort = subjectAdmissionPort;
        this.spaceMembershipPort = spaceMembershipPort;
        this.tagTermPort = tagTermPort;
        this.categoryPort = categoryPort;
        this.clock = clock;
    }

    /**
     * 登记（行为 1 全部规则）：资格门槛（防枚举同形）→ 空间状态门槛 → 成员门槛（DENIED 留痕）→
     * 重要数据拒收（DENIED 留痕）→ 语义标签成员校验（第 6′ 步）→ 类目成员校验（组内后位）→
     * 名称锁定/同空间判重（DB 唯一键兜底）→ 取号 + INSERT + 留痕同事务。成员校验位于幂等切面之内：
     * 重放命中按 ADR-007 模式 B 回放首次结果。
     */
    @Idempotent(key = "'REGISTER:' + #cmd.spaceId + ':' + #cmd.ownerSubjectNo + ':' + #cmd.normalizedName")
    public Dataset register(final CreateDatasetCommand cmd) {
        requireAdmitted(cmd);
        requireActiveSpaceAndMember(cmd);
        rejectImportantDeclaration(cmd);
        requireTermMembership(cmd);
        requireCategoryMembership(cmd);
        if (repository.existsInNameLock(cmd.spaceId(), cmd.normalizedName())) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_NAME_DUPLICATED,
                    CatalogErrorCodes.DATASET_NAME_LOCKED_MESSAGE);
        }
        if (repository.existsBySpaceAndNormalizedName(cmd.spaceId(), cmd.normalizedName())) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_NAME_DUPLICATED,
                    CatalogErrorCodes.DATASET_NAME_DUPLICATED_MESSAGE);
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        final Dataset dataset = new Dataset(null, generateDataNo(now), cmd.spaceId(), cmd.ownerSubjectNo(),
                cmd.name(), cmd.normalizedName(), cmd.type(), cmd.intro(),
                SemanticTags.toJson(cmd.semanticTags()), cmd.declareCategory(), cmd.declareLevel(),
                false, DatasetStatus.ACTIVE, now, now);
        final DatasetActionLog log = new DatasetActionLog(null, cmd.ownerSubjectNo(), cmd.spaceId(), null,
                ACTION_REGISTER, null, DatasetStatus.ACTIVE.name(), ActionResult.SUCCESS, null, now);
        final long id = repository.create(dataset, log);
        return repository.findById(id).orElseThrow();
    }

    // ==== 内部：门槛与要素 ====

    /** 资格门槛三态（行为 1 规则 1）：NOT_ADMITTED → 统一文案（防枚举）；UNAVAILABLE → 1007S0001。 */
    private void requireAdmitted(final CreateDatasetCommand cmd) {
        final SubjectAdmission admission = subjectAdmissionPort.check(cmd.ownerSubjectNo());
        if (admission == SubjectAdmission.NOT_ADMITTED) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_FORBIDDEN,
                    CatalogErrorCodes.ADMISSION_REQUIRED_MESSAGE);
        }
        if (admission == SubjectAdmission.UNAVAILABLE) {
            throw new CatalogBizException(CatalogErrorCodes.SUBJECT_SERVICE_UNAVAILABLE,
                    CatalogErrorCodes.SUBJECT_SERVICE_UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * 空间状态门槛 + 成员门槛（行为 1 规则 1/2）：空间服务不可达 → 1007S0002（不冒充非成员）；
     * 空间状态非 ACTIVE（含 NONE 空间不存在同形）→ 1007C0002；非成员 → 1007C0006 + DENIED 留痕。
     */
    private void requireActiveSpaceAndMember(final CreateDatasetCommand cmd) {
        final SpaceMembership membership = spaceMembershipPort.check(cmd.spaceId(), cmd.ownerSubjectNo());
        if (!membership.available()) {
            throw new CatalogBizException(CatalogErrorCodes.SPACE_SERVICE_UNAVAILABLE,
                    CatalogErrorCodes.SPACE_SERVICE_UNAVAILABLE_MESSAGE);
        }
        if (!membership.isSpaceActive()) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_SPACE_STATE_FORBIDDEN,
                    CatalogErrorCodes.DATASET_SPACE_STATE_FORBIDDEN_MESSAGE);
        }
        if (!membership.isMember()) {
            insertDenied(cmd, CatalogErrorCodes.DATASET_FORBIDDEN);
            throw new CatalogBizException(CatalogErrorCodes.DATASET_FORBIDDEN,
                    CatalogErrorCodes.DATASET_FORBIDDEN_MESSAGE);
        }
    }

    /** 重要数据拒收（行为 1 规则 4；分级规范 §4.5-3 硬约束）：代码强制 + DENIED 留痕。 */
    private void rejectImportantDeclaration(final CreateDatasetCommand cmd) {
        if (cmd.declareImportant()) {
            insertDenied(cmd, CatalogErrorCodes.DATASET_IMPORTANT_REJECTED);
            throw new CatalogBizException(CatalogErrorCodes.DATASET_IMPORTANT_REJECTED,
                    CatalogErrorCodes.DATASET_IMPORTANT_REJECTED_MESSAGE);
        }
    }

    /**
     * 语义标签词条成员校验（WBS-3.3.3 hifi §4.1 第 6′ 步，行为 1 规则 3）：任一标签不在受控词表 →
     * 1007C0009（400）。拒绝文案为服务端常量、不回显被拒标签原文；此处不写 DENIED 留痕
     * （非法入参零库内副作用——零资源行、零留痕、零取号）。
     */
    private void requireTermMembership(final CreateDatasetCommand cmd) {
        if (!tagTermPort.findUnmatched(TagVocabulary.SEMANTIC_TAG, cmd.semanticTags()).isEmpty()) {
            throw new CatalogBizException(CatalogErrorCodes.TAG_TERM_NOT_IN_VOCABULARY,
                    CatalogErrorCodes.TAG_TERM_NOT_IN_VOCABULARY_MESSAGE);
        }
    }

    /**
     * 类目成员校验（WBS-3.3.4 hifi §4.1 组内后位，兑现 3.3.2 移交"类目树校验"）：申报值经
     * {@link DatasetNameNormalizer} 归一化后不在受控类目集合 → 1007C0014（400）。拒绝文案为
     * 服务端常量、不回显申报原文（沿 1007C0009 文案口径）；此处不写 DENIED 留痕（非法入参零库内
     * 副作用——零资源行、零留痕、零取号）；只作用新写入、不回填历史（沿 3.3.3 Q6-A）。
     */
    private void requireCategoryMembership(final CreateDatasetCommand cmd) {
        if (!categoryPort.existsByNormalizedName(DatasetNameNormalizer.normalize(cmd.declareCategory()))) {
            throw new CatalogBizException(CatalogErrorCodes.CATEGORY_NOT_IN_CONTROLLED_TREE,
                    CatalogErrorCodes.CATEGORY_NOT_IN_CONTROLLED_TREE_MESSAGE);
        }
    }

    /** 生成数据标识（Q3-A）：DS + yyyyMMdd + 6 位当日序号（序号表原子自增、当日重置）。 */
    private String generateDataNo(final LocalDateTime now) {
        final LocalDate today = now.toLocalDate();
        final int seq = repository.nextDailySeq(today);
        return DATA_NO_PREFIX + DATA_NO_DATE.format(today) + String.format("%06d", seq);
    }

    /** 登记拒绝留痕（DENIED，datasetId 未成行 = NULL；理由以错误码尾号承载，hifi §3.3）。 */
    private void insertDenied(final CreateDatasetCommand cmd, final ErrorCode errorCode) {
        repository.insertLog(new DatasetActionLog(null, cmd.ownerSubjectNo(), cmd.spaceId(), null,
                ACTION_DENIED_REGISTER, null, null, ActionResult.DENIED,
                CatalogErrorCodes.tailOf(errorCode), LocalDateTime.now(clock)));
    }
}
