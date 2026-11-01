package com.ctds.metering.application;

import com.ctds.metering.domain.MeteringFlow;
import com.ctds.metering.domain.MeteringFlowRepository;
import com.ctds.metering.domain.MeteringSourceReader;
import com.ctds.metering.domain.MeteringSourceType;
import com.ctds.metering.domain.SourceRow;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采集编排（R-M0，hifi §3）：单事务逐腿只读扫描 + upsert 幂等落库，任一源失败整体失败
 * 可重触发；不删除不更新——事件源删除/更正不回溯流水（V1.0 登记演进）。
 * 通道 1（网关事件）不在本事务内——JdbcMeteringAuditRecorder 异步落库，与采集互不阻塞。
 */
@Service
public class MeteringCollectService {

    private final MeteringSourceReader sourceReader;
    private final MeteringFlowRepository flowRepository;

    public MeteringCollectService(final MeteringSourceReader sourceReader,
            final MeteringFlowRepository flowRepository) {
        this.sourceReader = sourceReader;
        this.flowRepository = flowRepository;
    }

    @Transactional
    public CollectOutcome collect() {
        final DeliveryLeg delivery = collectDeliveryLeg();
        final ScannedCollected sandboxJob = collectSandboxLeg();
        final long scannedTotal = delivery.file.scanned() + delivery.export.scanned()
                + sandboxJob.scanned();
        final long collectedTotal = delivery.file.collected() + delivery.export.collected()
                + sandboxJob.collected();
        return new CollectOutcome(scannedTotal, collectedTotal,
                new SourceCounts(delivery.file(), delivery.export(), sandboxJob));
    }

    /** 交付腿：FILE 与 SANDBOX_EXPORT 行同查询返回，按映射类型分组计数（排除 API 行——B7-3）。 */
    private DeliveryLeg collectDeliveryLeg() {
        final List<SourceRow> rows = sourceReader.readDeliveryRows();
        long fileScanned = 0;
        long fileCollected = 0;
        long exportScanned = 0;
        long exportCollected = 0;
        for (final SourceRow row : rows) {
            if (row.sourceType() == MeteringSourceType.FILE_DELIVERY) {
                fileScanned++;
                if (upsert(row)) {
                    fileCollected++;
                }
            } else if (row.sourceType() == MeteringSourceType.SANDBOX_EXPORT) {
                exportScanned++;
                if (upsert(row)) {
                    exportCollected++;
                }
            }
        }
        return new DeliveryLeg(new ScannedCollected(fileScanned, fileCollected),
                new ScannedCollected(exportScanned, exportCollected));
    }

    /** 沙箱腿：sandbox_job(SUCCEEDED) 经 JOIN 归属（只取 SUCCEEDED 侧 = 拒绝零计量结构性）。 */
    private ScannedCollected collectSandboxLeg() {
        final List<SourceRow> rows = sourceReader.readSandboxJobRows();
        long scanned = 0;
        long collected = 0;
        for (final SourceRow row : rows) {
            scanned++;
            if (upsert(row)) {
                collected++;
            }
        }
        return new ScannedCollected(scanned, collected);
    }

    private boolean upsert(final SourceRow row) {
        return flowRepository.upsert(new MeteringFlow(row.contractNo(), row.requesterNo(),
                row.sourceType(), row.sourceNo(), row.sourceType().defaultAction(), null,
                row.occurredAt()));
    }

    private record DeliveryLeg(ScannedCollected file, ScannedCollected export) {
    }
}
