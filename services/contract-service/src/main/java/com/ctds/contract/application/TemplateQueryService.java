package com.ctds.contract.application;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.ContractTemplate;
import com.ctds.contract.domain.ContractTemplateRepository;
import com.ctds.contract.domain.SubjectAdmission;
import com.ctds.contract.domain.SubjectAdmissionPort;
import com.ctds.contract.domain.TemplateActionLog;
import com.ctds.contract.domain.TemplateStatus;
import com.ctds.contract.domain.TemplateType;
import com.ctds.contract.domain.TemplateVersion;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 模板读面应用服务（浏览读面 / 运营读面 / 发起侧校验；WBS-3.4.2 hifi §2.2~§2.4）。
 *
 * <p>浏览边界（行为 1 规则 5）：已入驻门槛走 SubjectAdmissionPort 三态——NOT_ADMITTED 与主体
 * 不存在同码同文案（1008C0003 防枚举）；UNAVAILABLE → 1008S0001 不冒充。浏览面仅呈现启用中
 * 模板（Q6-A）：停用模板详情与不存在同码同文案（1008C0001，防枚举）。运营读面门槛由
 * {@code contract.template.manage} 注解承载（R1~R3，读面无规格留痕义务）。</p>
 */
@Service
public class TemplateQueryService {

    private final ContractTemplateRepository repository;
    private final SubjectAdmissionPort subjectAdmissionPort;

    public TemplateQueryService(final ContractTemplateRepository repository,
            final SubjectAdmissionPort subjectAdmissionPort) {
        this.repository = repository;
        this.subjectAdmissionPort = subjectAdmissionPort;
    }

    /** R4 已入驻浏览列表（仅启用中——Q6-A；type 可选过滤，分页）。 */
    public PageResult<ContractTemplate> browseList(final String subjectNo, final Integer pageNum,
            final Integer pageSize, final TemplateType type) {
        requireAdmitted(subjectNo);
        return repository.pageEnabled(type, PageQuery.of(pageNum, pageSize, null));
    }

    /** R5 已入驻浏览详情（当前版本条款框架全文；停用与不存在同形 1008C0001——剧本 S1-2/S2-4）。 */
    public TemplateWithVersion browseDetail(final String subjectNo, final String templateNo) {
        requireAdmitted(subjectNo);
        final ContractTemplate template = repository.findByNo(templateNo)
                .filter(t -> t.status() == TemplateStatus.ENABLED)
                .orElseThrow(() -> new ContractBizException(ContractErrorCodes.TEMPLATE_NOT_FOUND_OR_UNAVAILABLE,
                        ContractErrorCodes.TEMPLATE_NOT_FOUND_OR_UNAVAILABLE_MESSAGE));
        final TemplateVersion version = repository.findVersion(templateNo, template.currentVersion())
                .orElseThrow(() -> new ContractBizException(ContractErrorCodes.TEMPLATE_NOT_FOUND_OR_UNAVAILABLE,
                        ContractErrorCodes.TEMPLATE_NOT_FOUND_OR_UNAVAILABLE_MESSAGE));
        return new TemplateWithVersion(template, version);
    }

    /** R1 运营全量列表（含停用——运营维护视图，支持重新启用；status/type 可选过滤）。 */
    public PageResult<ContractTemplate> manageList(final TemplateStatus status, final TemplateType type,
            final Integer pageNum, final Integer pageSize) {
        return repository.pageManage(status, type, PageQuery.of(pageNum, pageSize, null));
    }

    /** R2 版本历史（旧版本保留可查——行为 1 规则 3；运营读面，hifi Q8-A）。 */
    public List<TemplateVersion> versions(final String templateNo) {
        requireTemplate(templateNo);
        return repository.listVersions(templateNo);
    }

    /** R3 留痕分页（四要素 + from→to + 拒绝理由码；templateNo 可选过滤——剧本 S3-3）。 */
    public PageResult<TemplateActionLog> actionLogs(final String templateNo, final Integer pageNum,
            final Integer pageSize) {
        return repository.pageLogs(templateNo, PageQuery.of(pageNum, pageSize, null));
    }

    /**
     * QV1 发起侧校验（供 3.4.3 发起流程锁定版本快照前调用）：有效 = 模板存在 + 版本存在 +
     * 模板启用中；无效三态见 {@code InitiationCheck.InitiationInvalidReason}（含"停用模板不可
     * 新发起"——发起侧拒绝的兑现归 3.4.3，本卡交付校验能力，移交义务双登记）。
     */
    public InitiationCheck validateForInitiation(final String templateNo, final int versionNo) {
        final ContractTemplate template = repository.findByNo(templateNo).orElse(null);
        if (template == null) {
            return InitiationCheck.invalid(InitiationCheck.InitiationInvalidReason.TEMPLATE_NOT_FOUND);
        }
        if (template.status() == TemplateStatus.DISABLED) {
            return InitiationCheck.invalid(InitiationCheck.InitiationInvalidReason.TEMPLATE_DISABLED);
        }
        if (repository.findVersion(templateNo, versionNo).isEmpty()) {
            return InitiationCheck.invalid(InitiationCheck.InitiationInvalidReason.VERSION_NOT_FOUND);
        }
        return InitiationCheck.ok();
    }

    /** QV2 版本快照读取（3.4.3 锁定快照时的内容来源；版本不存在返回空）。 */
    public java.util.Optional<TemplateVersion> loadFramework(final String templateNo, final int versionNo) {
        return repository.findVersion(templateNo, versionNo);
    }

    // ==== 内部：浏览资格门槛 ====

    /** 已入驻门槛三态（行为 1 规则 5）：未入驻/不存在统一文案（1008C0003）；不可用不冒充（1008S0001）。 */
    private void requireAdmitted(final String subjectNo) {
        final SubjectAdmission admission = subjectAdmissionPort.check(subjectNo);
        if (admission == SubjectAdmission.NOT_ADMITTED) {
            throw new ContractBizException(ContractErrorCodes.ADMISSION_REQUIRED,
                    ContractErrorCodes.BROWSE_ADMISSION_REQUIRED_MESSAGE);
        }
        if (admission == SubjectAdmission.UNAVAILABLE) {
            throw new ContractBizException(ContractErrorCodes.SUBJECT_SERVICE_UNAVAILABLE,
                    ContractErrorCodes.SUBJECT_SERVICE_UNAVAILABLE_MESSAGE);
        }
    }

    /** 运营面模板存在性（直述 1008C0006，不防枚举）。 */
    private void requireTemplate(final String templateNo) {
        if (repository.findByNo(templateNo).isEmpty()) {
            throw new ContractBizException(ContractErrorCodes.TEMPLATE_NOT_FOUND,
                    ContractErrorCodes.TEMPLATE_NOT_FOUND_MESSAGE);
        }
    }
}
