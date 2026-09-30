package com.ctds.catalog.application;

import com.ctds.catalog.domain.ActionResult;
import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.Dataset;
import com.ctds.catalog.domain.DatasetActionLog;
import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 资源读面服务（WBS-3.3.2 hifi §1.1 R1/R2）。
 *
 * <p>读面边界（行为 7 规则 1/2）：列表恒仅本人资源（createdAt 倒序）；详情仅本人可读，
 * 非本人与"不存在"一律同形拒绝（1007C0005，防枚举）+ 对内 DENIED 留痕（行为 7 规则 4
 * 越权探测留痕——对外形态不变、对内可审计）。响应恒仅为目录元数据，不含数据本体（行为 7 规则 5）。</p>
 */
@Service
public class DatasetQueryService {

    /** 读面拒绝留痕动作码（值域 = 动作 + DENIED_ 前缀变体，hifi §3.3）。 */
    private static final String ACTION_DENIED_READ = "DENIED_READ";

    private final DatasetRepository repository;
    private final CatalogAccessGuard guard;
    private final Clock clock;

    public DatasetQueryService(final DatasetRepository repository, final CatalogAccessGuard guard,
            final Clock clock) {
        this.repository = repository;
        this.guard = guard;
        this.clock = clock;
    }

    /** 本人资源分页列表（spaceId 可选过滤；仅 owner_subject_no 命中，createdAt 倒序）。 */
    public PageResult<Dataset> mine(final Integer pageNum, final Integer pageSize, final Long spaceId) {
        final String subject = guard.requireSubject();
        return repository.searchByOwner(subject, spaceId, PageQuery.of(pageNum, pageSize, null));
    }

    /**
     * 本人资源详情：非本人（含空间管理员与其他成员）与不存在同形拒绝（1007C0005，防枚举）；
     * 资源存在但非本人时落 DENIED_READ 拒绝留痕（越权探测留痕）。
     */
    public Dataset detail(final long datasetId) {
        final String subject = guard.requireSubject();
        final Dataset dataset = repository.findById(datasetId)
                .orElseThrow(DatasetQueryService::notFoundOrNoAccess);
        if (!dataset.ownerSubjectNo().equals(subject)) {
            repository.insertLog(new DatasetActionLog(null, subject, dataset.spaceId(), dataset.id(),
                    ACTION_DENIED_READ, dataset.status().name(), null, ActionResult.DENIED,
                    CatalogErrorCodes.tailOf(CatalogErrorCodes.DATASET_NOT_FOUND_OR_NO_ACCESS),
                    LocalDateTime.now(clock)));
            throw notFoundOrNoAccess();
        }
        return dataset;
    }

    /** 资源不存在/无权访问（统一 1007C0005，读面防枚举同形）。 */
    private static CatalogBizException notFoundOrNoAccess() {
        return new CatalogBizException(CatalogErrorCodes.DATASET_NOT_FOUND_OR_NO_ACCESS,
                CatalogErrorCodes.DATASET_NOT_FOUND_OR_NO_ACCESS_MESSAGE);
    }
}
