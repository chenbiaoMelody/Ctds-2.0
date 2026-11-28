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

class SubjectDenialRateLimiterTest {

    private static final Instant START = Instant.parse("2026-11-24T14:00:00Z");

    @Test
    void allowsOneHundredThenRejectsWithRetryAfter() {
        final SubjectDenialRateLimiter limiter = new SubjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 100, 100);
        for (int request = 0; request < 100; request++) {
            limiter.check("subject", "DENIED_READ");
        }
        assertThatThrownBy(() -> limiter.check("subject", "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class)
                .satisfies(ex -> assertThat(((CatalogRateLimitException) ex).getRetryAfterSeconds())
                        .isEqualTo(60));
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThatThrownBy(() -> new SubjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resetsAtNextMinuteAndReportsOneSecondAtBoundary() {
        final SubjectDenialRateLimiter limiter = new SubjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 100);
        limiter.check("subject", "DENIED_READ");
        assertThatThrownBy(() -> limiter.check("subject", "DENIED_READ", START.plusSeconds(59)))
                .isInstanceOf(CatalogRateLimitException.class)
                .satisfies(ex -> assertThat(((CatalogRateLimitException) ex).getRetryAfterSeconds())
                        .isEqualTo(1));
        limiter.check("subject", "DENIED_READ", START.plusSeconds(60));
    }

    @Test
    void keysAreIndependentBySubjectAndAction() {
        final SubjectDenialRateLimiter limiter = new SubjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 100);
        limiter.check("subject-a", "DENIED_READ");
        limiter.check("subject-b", "DENIED_READ");
        limiter.check("subject-a", "DENIED_UPDATE");
    }

    @Test
    void releasedFailureReservationRestoresQuota() {
        final SubjectDenialRateLimiter limiter = new SubjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 100);
        final SubjectDenialRateLimiter.Reservation reservation =
                limiter.check("subject", "DENIED_READ");
        limiter.release(reservation);
        limiter.check("subject", "DENIED_READ");
    }

    @Test
    void staleReservationDoesNotReleaseRecreatedKeyAfterEviction() {
        final SubjectDenialRateLimiter limiter = new SubjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 1);
        final SubjectDenialRateLimiter.Reservation stale =
                limiter.check("subject-a", "DENIED_READ");
        limiter.check("subject-b", "DENIED_READ");
        limiter.check("subject-a", "DENIED_READ");
        limiter.release(stale);
        assertThatThrownBy(() -> limiter.check("subject-a", "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
    }

    @Test
    void evictsLeastRecentlyUsedKeyWhenCapacityIsReached() {
        final SubjectDenialRateLimiter limiter = new SubjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 2);
        limiter.check("subject-a", "DENIED_READ");
        limiter.check("subject-b", "DENIED_READ");
        assertThatThrownBy(() -> limiter.check("subject-a", "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
        limiter.check("subject-c", "DENIED_READ");
        assertThatThrownBy(() -> limiter.check("subject-c", "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
        assertThatThrownBy(() -> limiter.check("subject-a", "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
        limiter.check("subject-b", "DENIED_READ");
        assertThatThrownBy(() -> limiter.check("subject-b", "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
    }

    @Test
    void boundsTrackedKeysAtTenThousand() {
        final SubjectDenialRateLimiter limiter = new SubjectDenialRateLimiter(
                Clock.fixed(START, ZoneOffset.UTC), 1, 10_000);
        for (int subject = 0; subject < 10_000; subject++) {
            limiter.check("subject-" + subject, "DENIED_READ");
        }
        assertThatThrownBy(() -> limiter.check("subject-0", "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
        limiter.check("subject-10000", "DENIED_READ");
        assertThatThrownBy(() -> limiter.check("subject-2", "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
        limiter.check("subject-1", "DENIED_READ");
        assertThatThrownBy(() -> limiter.check("subject-1", "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
        assertThatThrownBy(() -> limiter.check("subject-10000", "DENIED_READ"))
                .isInstanceOf(CatalogRateLimitException.class);
    }

    @Test
    void concurrentChecksAdmitExactlyThreshold() throws Exception {
        final SubjectDenialRateLimiter limiter = new SubjectDenialRateLimiter(
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
                        limiter.check("subject", "DENIED_READ");
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
