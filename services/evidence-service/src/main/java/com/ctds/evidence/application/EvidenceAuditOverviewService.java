package com.ctds.evidence.application;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.evidence.domain.EvidenceAuditQueryRepository;
import com.ctds.evidence.domain.EvidenceAuditQueryRepository.StageStatusCount;
import com.ctds.evidence.domain.EvidenceDigests;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 监管审计总览与异常项应用服务（WBS-3.8.4 hifi §1 E1 / E2；规格 B5-2① / B5-2②；剧本 S1-1）：
 * 四环节 × 三状态计数矩阵（键恒定，缺省补零）+ 异常三计数（含核验不一致跨笔）+
 * 异常项三类并集分页。全部只读。
 */
@Service
public class EvidenceAuditOverviewService {

    /** 环节键序（矩阵恒定形状）。 */
    private static final List<String> STAGES = List.of("AUTHORIZATION", "CONTRACT",
            "DELIVERY", "USAGE");

    private final EvidenceAuditQueryRepository repository;
    private final Clock clock;

    public EvidenceAuditOverviewService(final EvidenceAuditQueryRepository repository,
            final Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** E1 总览：from/to 可选（同时给，跨度 ≤90 天，非法 → 1012C0002）。 */
    public EvidenceAuditViews.OverviewView overview(final String from, final String to) {
        final var criteria = EvidenceAuditConditions.parse(null, null, null, from, to, false);
        final Map<String, EvidenceAuditViews.StageStatusView> matrix = new LinkedHashMap<>();
        long pending = 0;
        long failed = 0;
        final Map<String, long[]> counts = new LinkedHashMap<>();
        for (final String stage : STAGES) {
            counts.put(stage, new long[3]);
        }
        for (final StageStatusCount row : repository.countByStageAndStatus(criteria.from(),
                criteria.to())) {
            final long[] cell = counts.get(row.stageType());
            if (cell != null) {
                cell[indexOfStatus(row.status())] = row.count();
            }
        }
        for (final String stage : STAGES) {
            final long[] cell = counts.get(stage);
            matrix.put(stage, new EvidenceAuditViews.StageStatusView(cell[0], cell[1], cell[2]));
            pending += cell[0];
            failed += cell[2];
        }
        final long mismatch = repository.countMismatch(criteria.from(), criteria.to());
        return new EvidenceAuditViews.OverviewView(matrix,
                new EvidenceAuditViews.AnomalyCountsView(pending, failed, mismatch),
                EvidenceDigests.occurredAtIso(clock.instant()));
    }

    /** E2 异常项清单：from/to 可选 + 分页；三类并集一行一条，MISMATCH 行附最新核验结论与核验人。 */
    public PageResult<EvidenceAuditViews.AnomalyItemView> anomalies(final String from,
            final String to, final Integer pageNum, final Integer pageSize) {
        final var criteria = EvidenceAuditConditions.parse(null, null, null, from, to, false);
        final int pn = pageNum == null ? 1 : pageNum;
        final int ps = pageSize == null ? 10 : pageSize;
        if (pn < 1 || pn > EvidenceAuditLimits.MAX_PAGE_NUM) {
            throw EvidenceAuditConditions.pageNumOutOfRange();
        }
        if (ps < 1 || ps > EvidenceAuditLimits.MAX_PAGE_SIZE) {
            throw EvidenceAuditConditions.pageSizeOutOfRange();
        }
        final PageQuery pageQuery = PageQuery.of(pn, ps, null);
        final var page = repository.pageAnomalies(criteria, (int) pageQuery.offset(), ps);
        final List<EvidenceAuditViews.AnomalyItemView> items = page.list().stream()
                .map(row -> EvidenceAuditViews.AnomalyItemView.of(
                        EvidenceRecordView.from(row.record()), row))
                .toList();
        return PageResult.of(items, page.total(), pageQuery);
    }

    private static int indexOfStatus(final String status) {
        return switch (status) {
            case "PENDING" -> 0;
            case "ON_CHAIN" -> 1;
            case "FAILED" -> 2;
            default -> throw new IllegalStateException("未知状态计数行: " + status);
        };
    }
}
