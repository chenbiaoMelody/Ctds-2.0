package com.ctds.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.catalog.domain.CatalogRateLimitException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ObjectDenialRateLimiterTest {

    private static final Instant START = Instant.parse("2026-11-24T14:00:00Z");

    @Test
    void allowsTwentyThenRejectsWithRetryAfter() {
        final ObjectDenialRateLimiter limiter = new ObjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 20, 100);
        for (int request = 0; request < 20; request++) {
            limiter.check("subject", 1L, "DENIED_READ");
        }
        assertThatThrownBy(() -> limiter.check("subject", 1L, "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class)
                .satisfies(ex -> assertThat(((CatalogRateLimitException) ex).getRetryAfterSeconds())
                        .isEqualTo(60));
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThatThrownBy(() -> new ObjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 20, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resetsAtNextMinuteAndReportsOneSecondAtBoundary() {
        final ObjectDenialRateLimiter limiter = new ObjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 100);
        limiter.check("subject", 1L, "DENIED_READ");
        assertThatThrownBy(() -> limiter.check("subject", 1L, "DENIED_READ", START.plusSeconds(59)))
                .isInstanceOf(CatalogRateLimitException.class)
                .satisfies(ex -> assertThat(((CatalogRateLimitException) ex).getRetryAfterSeconds())
                        .isEqualTo(1));
        limiter.check("subject", 1L, "DENIED_READ", START.plusSeconds(60));
    }

    @Test
    void keysAreIndependentBySubjectObjectAndAction() {
        final ObjectDenialRateLimiter limiter = new ObjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 100);
        limiter.check("subject-a", 1L, "DENIED_READ");
        limiter.check("subject-b", 1L, "DENIED_READ");
        limiter.check("subject-a", 2L, "DENIED_READ");
        limiter.check("subject-a", 1L, "DENIED_UPDATE");
    }

    @Test
    void releasedFailureReservationRestoresQuota() {
        final ObjectDenialRateLimiter limiter = new ObjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 100);
        final ObjectDenialRateLimiter.Reservation reservation =
                limiter.check("subject", 1L, "DENIED_READ");
        limiter.release(reservation);
        limiter.check("subject", 1L, "DENIED_READ");
    }

    @Test
    void staleReservationDoesNotReleaseRecreatedKeyAfterEviction() {
        final ObjectDenialRateLimiter limiter = new ObjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 1);
        final ObjectDenialRateLimiter.Reservation stale =
                limiter.check("subject", 1L, "DENIED_READ");
        limiter.check("subject", 2L, "DENIED_READ");
        limiter.check("subject", 1L, "DENIED_READ");
        limiter.release(stale);
        assertThatThrownBy(() -> limiter.check("subject", 1L, "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
    }

    @Test
    void staleReservationDoesNotReleaseNextMinute() {
        final ObjectDenialRateLimiter limiter = new ObjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 100);
        final ObjectDenialRateLimiter.Reservation stale =
                limiter.check("subject", 1L, "DENIED_READ", START);
        limiter.check("subject", 1L, "DENIED_READ", START.plusSeconds(60));
        limiter.release(stale);
        assertThatThrownBy(() -> limiter.check("subject", 1L, "DENIED_READ", START.plusSeconds(61)))
                .isInstanceOf(CatalogRateLimitException.class);
    }

    @Test
    void evictsLeastRecentlyUsedKeyWhenCapacityIsReached() {
        final ObjectDenialRateLimiter limiter = new ObjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 2);
        limiter.check("subject", 1L, "DENIED_READ");
        limiter.check("subject", 2L, "DENIED_READ");
        assertThatThrownBy(() -> limiter.check("subject", 1L, "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
        limiter.check("subject", 3L, "DENIED_READ");
        assertThatThrownBy(() -> limiter.check("subject", 3L, "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
        assertThatThrownBy(() -> limiter.check("subject", 1L, "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
        limiter.check("subject", 2L, "DENIED_READ");
        assertThatThrownBy(() -> limiter.check("subject", 2L, "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
    }

    @Test
    void boundsTrackedKeysAtTenThousand() {
        final ObjectDenialRateLimiter limiter = new ObjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 10_000);
        for (long datasetId = 0; datasetId < 10_000; datasetId++) {
            limiter.check("subject", datasetId, "DENIED_READ");
        }
        assertThatThrownBy(() -> limiter.check("subject", 0L, "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
        limiter.check("subject", 10_000L, "DENIED_READ");
        assertThatThrownBy(() -> limiter.check("subject", 2L, "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
        limiter.check("subject", 1L, "DENIED_READ");
        assertThatThrownBy(() -> limiter.check("subject", 1L, "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
        assertThatThrownBy(() -> limiter.check("subject", 10_000L, "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
    }

    @Test
    void concurrentChecksAdmitExactlyThreshold() throws Exception {
        final ObjectDenialRateLimiter limiter = new ObjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 20, 100);
        final CountDownLatch ready = new CountDownLatch(40);
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(40);
        try {
            final List<Callable<Boolean>> calls = new ArrayList<>();
            for (int request = 0; request < 40; request++) {
                calls.add(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        limiter.check("subject", 1L, "DENIED_READ");
                        return true;
                    } catch (CatalogRateLimitException ex) {
                        return false;
                    }
                });
            }
            final List<Future<Boolean>> futures = new ArrayList<>();
            for (final Callable<Boolean> call : calls) {
                futures.add(executor.submit(call));
            }
            ready.await();
            start.countDown();
            int admitted = 0;
            for (final Future<Boolean> future : futures) {
                if (future.get()) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(20);
        } finally {
            executor.shutdownNow();
        }
    }
}
