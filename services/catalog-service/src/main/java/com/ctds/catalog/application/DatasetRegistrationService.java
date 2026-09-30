package com.ctds.catalog.application;

import com.ctds.catalog.domain.ActionResult;
import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.Dataset;
import com.ctds.catalog.domain.DatasetActionLog;
import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.catalog.domain.DatasetStatus;
import com.ctds.catalog.domain.SemanticTags;
import com.ctds.catalog.domain.SpaceMembership;
import com.ctds.catalog.domain.SpaceMembershipPort;
import com.ctds.catalog.domain.SubjectAdmission;
import com.ctds.catalog.domain.SubjectAdmissionPort;
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
 * <p>登记事务顺序（hifi §4.2）：资格三态 → 空间状态门槛（NONE/CREATED/FROZEN/DISSOLVED 一律
 * 1007C0002）→ 成员门槛（NONE → 1007C0006 + DENIED 留痕）→ 重要数据拒收（1007C0003 + DENIED
 * 留痕，代码强制/§4.5-3 硬约束）→ 名称锁定与判重（1007C0001）→ 取号 + INSERT + REGISTER 留痕。</p>
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
    private final Clock clock;

    public DatasetRegistrationService(final DatasetRepository repository,
            final SubjectAdmissionPort subjectAdmissionPort, final SpaceMembershipPort spaceMembershipPort,
            final Clock clock) {
        this.repository = repository;
        this.subjectAdmissionPort = subjectAdmissionPort;
        this.spaceMembershipPort = spaceMembershipPort;
        this.clock = clock;
    }

    /**
     * 登记（行为 1 全部规则）：资格门槛（防枚举同形）→ 空间状态门槛 → 成员门槛（DENIED 留痕）→
     * 重要数据拒收（DENIED 留痕）→ 名称锁定/同空间判重（DB 唯一键兜底）→ 取号 + INSERT + 留痕同事务。
     */
    @Idempotent(key = "'REGISTER:' + #cmd.spaceId + ':' + #cmd.ownerSubjectNo + ':' + #cmd.normalizedName")
    public Dataset register(final CreateDatasetCommand cmd) {
        requireAdmitted(cmd);
        requireActiveSpaceAndMember(cmd);
        rejectImportantDeclaration(cmd);
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
