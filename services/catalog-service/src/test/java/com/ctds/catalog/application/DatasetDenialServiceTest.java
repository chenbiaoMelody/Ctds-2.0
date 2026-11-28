package com.ctds.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ctds.catalog.domain.CatalogRateLimitException;
import com.ctds.catalog.domain.DatasetActionLog;
import com.ctds.catalog.domain.DatasetRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

class DatasetDenialServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T14:00:30Z"),
            ZoneOffset.UTC);

    @Test
    void writesSameShapeWithStableMinuteDedupKey() {
        final DatasetRepository repository = mock(DatasetRepository.class);
        final DatasetDenialService service = new DatasetDenialService(repository,
                new ObjectDenialRateLimiter(CLOCK, 20, 100),
                new SubjectDenialRateLimiter(CLOCK, 100, 100), CLOCK);

        service.record("subject", "DENIED_READ", 42L);
        service.record("subject", "DENIED_READ", 42L);

        final ArgumentCaptor<DatasetActionLog> logs = ArgumentCaptor.forClass(DatasetActionLog.class);
        final ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).insertDeniedLog(logs.capture(), keys.capture());
        final long epochMinute = CLOCK.instant().getEpochSecond() / ObjectDenialRateLimiter.WINDOW_SECONDS;
        final String expectedKey = "7:subject|42|DENIED_READ|C0003|" + epochMinute;
        assertThat(keys.getAllValues()).containsOnly(expectedKey);
        assertThat(logs.getAllValues()).allSatisfy(log -> {
            assertThat(log.actorSubjectNo()).isEqualTo("subject");
            assertThat(log.spaceId()).isNull();
            assertThat(log.datasetId()).isEqualTo(42L);
            assertThat(log.action()).isEqualTo("DENIED_READ");
            assertThat(log.fromValue()).isNull();
            assertThat(log.toValue()).isNull();
            assertThat(log.result().name()).isEqualTo("DENIED");
            assertThat(log.reasonCode()).isEqualTo("C0003");
        });
    }

    @Test
    void authenticatedSubjectLengthsFitDenialDedupKey() {
        final DatasetRepository repository = mock(DatasetRepository.class);
        final DatasetDenialService service = new DatasetDenialService(repository,
                new ObjectDenialRateLimiter(CLOCK, 20, 100),
                new SubjectDenialRateLimiter(CLOCK, 100, 100), CLOCK);
        final ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);

        for (final int length : List.of(25, 108, 128)) {
            service.record("s".repeat(length), "DENIED_CANCEL", Long.MAX_VALUE);
        }

        verify(repository, times(3)).insertDeniedLog(any(), keys.capture());
        final List<Integer> lengths = List.of(25, 108, 128);
        for (int index = 0; index < lengths.size(); index++) {
            final int length = lengths.get(index);
            assertThat(keys.getAllValues().get(index))
                    .as("主体 %s 字符的去重键必须完整保留主体", length)
                    .startsWith(length + ":" + "s".repeat(length));
            assertThat(keys.getAllValues().get(index).length()).isLessThanOrEqualTo(255);
        }
    }

    @Test
    void auditFailurePropagatesWithoutFallback() {
        final DatasetRepository repository = mock(DatasetRepository.class);
        final long epochMinute = CLOCK.instant().getEpochSecond() / ObjectDenialRateLimiter.WINDOW_SECONDS;
        final String expectedKey = "7:subject|42|DENIED_READ|C0003|" + epochMinute;
        doThrow(new DataAccessResourceFailureException("audit down"))
                .when(repository).insertDeniedLog(any(), eq(expectedKey));
        final DatasetDenialService service = new DatasetDenialService(repository,
                new ObjectDenialRateLimiter(CLOCK, 20, 100),
                new SubjectDenialRateLimiter(CLOCK, 100, 100), CLOCK);

        assertThatThrownBy(() -> service.record("subject", "DENIED_READ", 42L))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("audit down");
    }

    @Test
    void auditFailureDoesNotConsumeRateLimitQuota() {
        final DatasetRepository repository = mock(DatasetRepository.class);
        doThrow(new DataAccessResourceFailureException("audit down"))
                .when(repository).insertDeniedLog(any(), any());
        final DatasetDenialService service = new DatasetDenialService(repository,
                new ObjectDenialRateLimiter(CLOCK, 1, 100),
                new SubjectDenialRateLimiter(CLOCK, 100, 100), CLOCK);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.record("subject", "DENIED_READ", 42L))
                    .isInstanceOf(DataAccessResourceFailureException.class)
                    .hasMessage("audit down");
        }
        verify(repository, times(3)).insertDeniedLog(any(), any());
    }

    @Test
    void subjectLimitStopsAuditForRotatingObjectIds() {
        final DatasetRepository repository = mock(DatasetRepository.class);
        final DatasetDenialService service = new DatasetDenialService(repository,
                new ObjectDenialRateLimiter(CLOCK, 20, 10_000),
                new SubjectDenialRateLimiter(CLOCK, 100, 100), CLOCK);

        for (long datasetId = 0; datasetId < 100; datasetId++) {
            service.record("subject", "DENIED_READ", datasetId);
        }

        assertThatThrownBy(() -> service.record("subject", "DENIED_READ", 100L))
                .isInstanceOf(CatalogRateLimitException.class);
        verify(repository, times(100)).insertDeniedLog(any(), any());
    }

    @Test
    void subjectLimitFailureReleasesObjectReservation() {
        final DatasetRepository repository = mock(DatasetRepository.class);
        final ObjectDenialRateLimiter objectLimiter =
                new ObjectDenialRateLimiter(CLOCK, 1, 100);
        final SubjectDenialRateLimiter subjectLimiter =
                new SubjectDenialRateLimiter(CLOCK, 1, 100);
        subjectLimiter.check("subject", "DENIED_READ");
        final DatasetDenialService service = new DatasetDenialService(repository,
                objectLimiter, subjectLimiter, CLOCK);

        assertThatThrownBy(() -> service.record("subject", "DENIED_READ", 42L))
                .isInstanceOf(CatalogRateLimitException.class);
        objectLimiter.check("subject", 42L, "DENIED_READ");
        verify(repository, times(0)).insertDeniedLog(any(), any());
    }

    @Test
    void auditFailureReleasesSubjectReservation() {
        final DatasetRepository repository = mock(DatasetRepository.class);
        doThrow(new DataAccessResourceFailureException("audit down"))
                .when(repository).insertDeniedLog(any(), any());
        final DatasetDenialService service = new DatasetDenialService(repository,
                new ObjectDenialRateLimiter(CLOCK, 20, 100),
                new SubjectDenialRateLimiter(CLOCK, 1, 100), CLOCK);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.record("subject", "DENIED_READ", 42L))
                    .isInstanceOf(DataAccessResourceFailureException.class)
                    .hasMessage("audit down");
        }
        verify(repository, times(3)).insertDeniedLog(any(), any());
    }
}
