package com.ctds.catalog.application;

import com.ctds.catalog.domain.ActionResult;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.Dataset;
import com.ctds.catalog.domain.DatasetActionLog;
import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.common.auth.AccessControl;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 资源读面服务（WBS-3.3.2 hifi §1.1 R1/R2）。
 *
 * <p>读面边界（行为 7 规则 1/2）：列表恒仅本人资源（createdAt 倒序）；详情仅本人可读，
 * 非本人与"不存在"一律同形拒绝（CHG-C-10.1-V1.3 裁决 A 案：400 + 1000C0003 + "资源不存在"，
 * 与对象不存在同码同文，防枚举）+ 对内 DENIED 留痕（行为 7 规则 4 越权探测留痕——对外形态不变、
 * 对内可审计）。响应恒仅为目录元数据，不含数据本体（行为 7 规则 5）。</p>
 */
@Service
public class DatasetQueryService {

    /** 读面拒绝留痕动作码（值域 = 动作 + DENIED_ 前缀变体，hifi §3.3）。 */
    private static final String ACTION_DENIED_READ = "DENIED_READ";

    /** 治理例外权限点（WBS-3.3.6 R14：admin 读留痕不另写留痕——hifi §1.2 读面留痕口径）。 */
    private static final String PERMISSION_GOVERNANCE = "catalog.governance";

    private final DatasetRepository repository;
    private final CatalogAccessGuard guard;
    private final AccessControl accessControl;
    private final Clock clock;

    public DatasetQueryService(final DatasetRepository repository, final CatalogAccessGuard guard,
            final AccessControl accessControl, final Clock clock) {
        this.repository = repository;
        this.guard = guard;
        this.accessControl = accessControl;
        this.clock = clock;
    }

    /** 本人资源分页列表（spaceId 可选过滤；仅 owner_subject_no 命中，createdAt 倒序）。 */
    public PageResult<Dataset> mine(final Integer pageNum, final Integer pageSize, final Long spaceId) {
        final String subject = guard.requireSubject();
        return repository.searchByOwner(subject, spaceId, PageQuery.of(pageNum, pageSize, null));
    }

    /**
     * 本人资源详情：非本人（含空间管理员、跨空间成员）与不存在同形拒绝（400 + 1000C0003 + 统一文案，
     * 规格 B3-R2/B4-R2 防枚举）；资源存在但非本人时落 DENIED_READ 拒绝留痕（reason_code C0003）。
     */
    public Dataset detail(final long datasetId) {
        final String subject = guard.requireSubject();
        final Dataset dataset = repository.findById(datasetId)
                .orElseThrow(DatasetQueryService::readNotFound);
        if (!dataset.ownerSubjectNo().equals(subject)) {
            repository.insertLog(new DatasetActionLog(null, subject, dataset.spaceId(), dataset.id(),
                    ACTION_DENIED_READ, dataset.status().name(), null, ActionResult.DENIED,
                    CatalogErrorCodes.tailOf(ErrorCodes.RESOURCE_NOT_FOUND),
                    LocalDateTime.now(clock)));
            throw readNotFound();
        }
        return dataset;
    }

    /**
     * R14 资源操作留痕分页（WBS-3.3.6 hifi §1.2）：读面 = 登记主体本人 或 治理例外
     * （{@code catalog.governance}，admin 读不另写留痕）；非本人且非治理档 → 400 + 1000C0003
     * 同形 + DENIED_READ 恰 1 行（沿 R2 同款口径）；对象不存在 → 同形拒绝、无留痕（无对象指向）。
     */
    public PageResult<DatasetActionLog> actionLogs(final long datasetId, final PageQuery page) {
        final String subject = guard.requireSubject();
        final Dataset dataset = repository.findById(datasetId)
                .orElseThrow(DatasetQueryService::readNotFound);
        final boolean owner = dataset.ownerSubjectNo().equals(subject);
        if (!owner && !accessControl.hasPermission(PERMISSION_GOVERNANCE)) {
            repository.insertLog(new DatasetActionLog(null, subject, dataset.spaceId(), dataset.id(),
                    ACTION_DENIED_READ, dataset.status().name(), null, ActionResult.DENIED,
                    CatalogErrorCodes.tailOf(ErrorCodes.RESOURCE_NOT_FOUND),
                    LocalDateTime.now(clock)));
            throw readNotFound();
        }
        return repository.pageLogsByDataset(datasetId, page);
    }

    /** 属主读面三态统一拒绝（400 + 1000C0003 + "资源不存在"，CHG-C-10.1-V1.3 裁决 A 案）。 */
    private static BizException readNotFound() {
        return new BizException(ErrorCodes.RESOURCE_NOT_FOUND,
                CatalogErrorCodes.DATASET_READ_NOT_FOUND_MESSAGE);
    }
}
