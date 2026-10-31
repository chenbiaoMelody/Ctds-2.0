package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.connector.application.ConnectorUpgradeService;
import com.ctds.connector.application.ConnectorVerificationService;
import com.ctds.connector.application.VerificationOutcome;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.support.ConnectorTestSupport;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 核验与升级并发门槛（WBS-3.6.8 W7，hifi §3 E12~E13；DB-72 / DB-76 / DB-73 Q5-A 部分承载）：
 * <ul>
 *   <li><b>T4-①</b>：并发双核验恰一成功——编号唯一、仅一条绑定、核验通过留痕恰一条
 *       （"四件事不落半成品"以并发唯一约束冲突承载）；</li>
 *   <li><b>T4-②</b>：同请求重复（顺序）提交 → 状态门槛拒绝；</li>
 *   <li><b>T4-③</b>：并发两条不兼容升级双写——恰一条待确认变更（唯一索引兜底可达，DB-76
 *       {@code catch DuplicateKeyException} 分支被真实触达）。</li>
 * </ul>
 */
class ConnectorVerificationConcurrencyTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000093";

    @Autowired
    private ConnectorVerificationService verificationService;

    @Autowired
    private ConnectorUpgradeService upgradeService;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentDualVerificationExactlyOneSucceeds() throws Exception {
        final String applicationNo = apply(OWNER, "race-verify");
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Future<VerificationOutcome> first = pool.submit((Callable<VerificationOutcome>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return verificationService.verify(OPERATOR, applicationNo);
            });
            final Future<VerificationOutcome> second = pool.submit((Callable<VerificationOutcome>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return verificationService.verify(OPERATOR, applicationNo);
            });
            final int successes = collectSuccess(first) + collectSuccess(second);
            assertThat(successes).as("并发双核验恰一成功").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_did_binding", Integer.class))
                .as("仅一条 ACTIVE 绑定（四件事不落半成品）").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_registration WHERE connector_no IS NOT NULL",
                Integer.class)).as("编号签发恰一次").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_action_log WHERE action = 'connector.verify.approved'",
                Integer.class)).as("核验通过留痕恰一条").isEqualTo(1);
    }

    @Test
    void duplicateSequentialVerificationRejected() throws Exception {
        final String applicationNo = apply(OWNER, "dup-verify");
        verificationService.verify(OPERATOR, applicationNo);
        assertThatThrownBy(() -> verificationService.verify(OPERATOR, applicationNo))
                .as("重复核验（同请求顺序重放）= 状态门槛拒绝")
                .isInstanceOfSatisfying(ConnectorBizException.class,
                        e -> assertThat(e.getErrorCode().value()).isEqualTo("1010C0004"));
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void concurrentIncompatibleUpgradesSinglePendingWins() throws Exception {
        for (int round = 0; round < 6; round++) {
            final int raceRound = round;
            final String connectorNo = registerConnector(OWNER, "race-upgrade-" + raceRound);
            final CyclicBarrier barrier = new CyclicBarrier(2);
            final ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                final Future<Boolean> one = pool.submit((Callable<Boolean>) () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    upgradeService.request(OWNER, connectorNo, "2.0." + raceRound, "INCOMPATIBLE");
                    return true;
                });
                final Future<Boolean> other = pool.submit((Callable<Boolean>) () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    upgradeService.request(OWNER, connectorNo, "3.0." + raceRound, "INCOMPATIBLE");
                    return true;
                });
                final boolean oneWon = await(one);
                final boolean otherWon = await(other);
                assertThat(oneWon ^ otherWon)
                        .as("第 " + round + " 轮：两条不兼容升级恰一成功（"
                                + oneWon + "/" + otherWon + "）")
                        .isTrue();
            } finally {
                pool.shutdownNow();
            }
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM connector_version_change WHERE connector_no = ?",
                    Integer.class, connectorNo))
                    .as("仅一条待确认变更（唯一索引兜底可达）").isEqualTo(1);
        }
    }

    private int collectSuccess(final Future<VerificationOutcome> future) {
        try {
            final VerificationOutcome outcome = future.get(60, TimeUnit.SECONDS);
            assertThat(outcome.verified()).as("成功分支必须 verified=true").isTrue();
            return 1;
        } catch (final Exception e) {
            assertThat(causeCode(e)).as("失败分支 = 状态门槛 1010C0004").isEqualTo("1010C0004");
            return 0;
        }
    }

    private static String causeCode(final Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof final ConnectorBizException biz) {
                return biz.getErrorCode().value();
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean await(final Future<Boolean> future) {
        try {
            return future.get(90, TimeUnit.SECONDS);
        } catch (final Exception e) {
            return false;
        }
    }
}
