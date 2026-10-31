package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.connector.application.ConnectorCancellationService;
import com.ctds.connector.application.ConnectorOccupancyService;
import com.ctds.connector.domain.ConnectorOccupancyRepository;
import com.ctds.connector.support.ConnectorTestSupport;
import java.time.LocalDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 占用登记原子谓词（WBS-3.6.8 W5 / DB-75，hifi §1-F）：
 * <ul>
 *   <li><b>T6-①</b>：SQL 谓词层直测——非 REGISTERED 连接器上仓储层占用零落行（服务层快路径可被并发
 *       绕过，原子性以 SQL 谓词为准）；</li>
 *   <li><b>T6-②</b>：真并发"注销 × 占用"恰一成功——CANCELLED 连接器上不得残留 OCCUPY 行，
 *       REGISTERED 连接器上占用成功则注销被拒（E12，无中间态）。</li>
 * </ul>
 */
class ConnectorOccupancyPredicateTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000092";

    @Autowired
    private ConnectorOccupancyRepository occupancyRepository;

    @Autowired
    private ConnectorOccupancyService occupancyService;

    @Autowired
    private ConnectorCancellationService cancellationService;

    @Test
    void repositoryOccupyOnCancelledConnectorLeavesNoRow() throws Exception {
        final String connectorNo = registerConnector(OWNER, "pred-cancelled");
        cancel(OWNER, APPLICANT_ROLE, connectorNo);
        // SQL 谓词层直测（绕过服务层快路径）：0 行落库 = 原子性以谓词为准（DB-75）
        occupancyRepository.occupy(connectorNo, "job-pred-1", LocalDateTime.now());
        assertThat(occupancyRepository.find(connectorNo, "job-pred-1"))
                .as("非 REGISTERED 连接器上仓储层占用零落行")
                .isEmpty();
    }

    @Test
    void repositoryOccupyOnRegisteredConnectorInsertsRow() throws Exception {
        final String connectorNo = registerConnector(OWNER, "pred-ok");
        occupancyRepository.occupy(connectorNo, "job-pred-2", LocalDateTime.now());
        assertThat(occupancyRepository.find(connectorNo, "job-pred-2"))
                .as("REGISTERED 连接器上仓储层占用落行（正腿对照）")
                .isPresent()
                .get()
                .satisfies(row -> assertThat(row.status().name()).isEqualTo("OCCUPY"));
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void concurrentCancelAndOccupyExactlyOneWins() throws Exception {
        for (int round = 0; round < 8; round++) {
            final int raceRound = round;
            final String connectorNo = registerConnector(OWNER, "pred-race-" + raceRound);
            final CyclicBarrier barrier = new CyclicBarrier(2);
            final ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                final AtomicReference<String> occupyFault = new AtomicReference<>();
                final AtomicReference<String> cancelFault = new AtomicReference<>();
                final Future<Boolean> occupyWon = pool.submit((Callable<Boolean>) () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    occupancyService.occupy("delivery-service", connectorNo, "job-race-" + raceRound);
                    return true;
                });
                final Future<Boolean> cancelWon = pool.submit((Callable<Boolean>) () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    cancellationService.cancel(OWNER, false, connectorNo);
                    return true;
                });
                final boolean occupied = await(occupyWon, occupyFault);
                final boolean cancelled = await(cancelWon, cancelFault);
                final String status = jdbc.queryForObject(
                        "SELECT status FROM connector_registration WHERE connector_no = ?",
                        String.class, connectorNo);
                final Integer occupyRows = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM connector_job_occupancy "
                                + "WHERE connector_no = ? AND status = 'OCCUPY'",
                        Integer.class, connectorNo);
                final String faultNote = occupyFault.get() == null && cancelFault.get() == null ? ""
                        : "，异常=[占用=" + occupyFault.get() + "，注销=" + cancelFault.get() + "]";
                assertThat(occupied ^ cancelled)
                        .as("第 " + round + " 轮：恰一成功（占用=" + occupied + "，注销=" + cancelled + "）"
                                + faultNote)
                        .isTrue();
                if (occupied) {
                    assertThat(status).as("占用成功 = 连接器保持 REGISTERED").isEqualTo("REGISTERED");
                    assertThat(occupyRows).as("占用成功 = OCCUPY 行在册").isEqualTo(1);
                } else {
                    assertThat(status).as("注销成功 = 连接器 CANCELLED").isEqualTo("CANCELLED");
                    assertThat(occupyRows).as("注销成功 = 不得残留 OCCUPY 行（E12）").isZero();
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /** await 失败显性化：业务拒绝（干净 false）与意外崩溃（异常记入 fault）在断言消息中可区分。 */
    private static boolean await(final Future<Boolean> future, final AtomicReference<String> fault) {
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (final Exception e) {
            fault.set(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            return false;
        }
    }
}
