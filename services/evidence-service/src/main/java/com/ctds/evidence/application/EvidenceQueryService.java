package com.ctds.evidence.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.evidence.domain.EvidenceDigests;
import com.ctds.evidence.domain.EvidenceErrorCodes;
import com.ctds.evidence.domain.EvidencePage;
import com.ctds.evidence.domain.EvidenceRecord;
import com.ctds.evidence.domain.EvidenceRepository;
import com.ctds.evidence.domain.EvidenceRules;
import com.ctds.evidence.domain.EvidenceSearchCriteria;
import com.ctds.evidence.domain.EvidenceStageType;
import com.ctds.evidence.domain.EvidenceStatus;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 存证读面应用服务（hifi §1.2 / §9 T10 / T11；规格 B4-2 / B5-2 最小承载）：
 * 清单（分页 + 过滤 + 时间窗 ≤90 天 + 禁止无过滤全量）、详情（记录 + 留痕正序）、
 * 异常概览（三状态计数 + 最近失败时点）。全部只读。
 */
@Service
public class EvidenceQueryService {

    private static final int MAX_PAGE_NUM = 10_000;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Duration MAX_WINDOW = Duration.ofDays(90);

    private final EvidenceRepository repository;

    public EvidenceQueryService(final EvidenceRepository repository) {
        this.repository = repository;
    }

    /** 清单：无任何过滤条件 / 时间窗 &gt;90 天 / 分页越界 → 400 1012C0002。 */
    public PageResult<EvidenceRecordView> page(final EvidenceSearchQuery query) {
        final EvidenceStageType stageType = (query.stageType() == null || query.stageType().isBlank())
                ? null : EvidenceStageType.parse(query.stageType());
        final EvidenceStatus status = (query.status() == null || query.status().isBlank())
                ? null : EvidenceStatus.parse(query.status());
        final String businessNo = normalizeBusinessNo(query.businessNo());
        final Instant from = (query.from() == null || query.from().isBlank())
                ? null : EvidenceRules.instant(query.from(), "起始时间");
        final Instant to = (query.to() == null || query.to().isBlank())
                ? null : EvidenceRules.instant(query.to(), "结束时间");
        if (from != null && to != null) {
            if (from.isAfter(to)) {
                throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "起始时间不得晚于结束时间");
            }
            if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
                throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "查询时间窗不得超过 90 天");
            }
        }
        final EvidenceSearchCriteria criteria =
                new EvidenceSearchCriteria(stageType, businessNo, status, from, to);
        if (criteria.isEmpty()) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "至少需要一个过滤条件");
        }

        final int pageNum = (query.pageNum() == null) ? 1 : query.pageNum();
        final int pageSize = (query.pageSize() == null) ? 10 : query.pageSize();
        if (pageNum < 1 || pageNum > MAX_PAGE_NUM) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "pageNum 越界");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "pageSize 越界");
        }
        final PageQuery pageQuery = PageQuery.of(pageNum, pageSize, null);
        final EvidencePage page = repository.page(criteria, (int) pageQuery.offset(), pageSize);
        return PageResult.of(page.list().stream().map(EvidenceRecordView::from).toList(),
                page.total(), pageQuery);
    }

    /** 详情：记录 + 状态流转与核验留痕（id 正序 = 时间正序）；不存在 → 404 1012C0001。 */
    public EvidenceDetailView detail(final String evidenceNo) {
        final EvidenceRecord record = repository.findByNo(evidenceNo)
                .orElseThrow(() -> new BizException(EvidenceErrorCodes.RECORD_NOT_FOUND,
                        EvidenceErrorCodes.RECORD_NOT_FOUND_MESSAGE));
        return new EvidenceDetailView(EvidenceRecordView.from(record),
                repository.findActions(record.evidenceNo()).stream()
                        .map(EvidenceActionView::from).toList());
    }

    /** 异常概览：三状态计数与记录实际状态一致；无失败记录时 lastFailedAt = null。 */
    public EvidenceOverviewView overview() {
        final String lastFailedAt = repository.lastFailedAt()
                .map(EvidenceDigests::occurredAtIso)
                .orElse(null);
        return new EvidenceOverviewView(
                repository.countByStatus(EvidenceStatus.ON_CHAIN),
                repository.countByStatus(EvidenceStatus.PENDING),
                repository.countByStatus(EvidenceStatus.FAILED),
                lastFailedAt);
    }

    private static String normalizeBusinessNo(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return EvidenceRules.businessNo(raw);
    }
}
