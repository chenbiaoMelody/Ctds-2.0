package com.ctds.catalog.application;

import com.ctds.catalog.domain.ActionResult;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.DatasetActionLog;
import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.common.errorcode.ErrorCodes;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class DatasetDenialService {

    private final DatasetRepository repository;
    private final ObjectDenialRateLimiter objectRateLimiter;
    private final SubjectDenialRateLimiter subjectRateLimiter;
    private final Clock clock;

    public DatasetDenialService(final DatasetRepository repository,
            final ObjectDenialRateLimiter objectRateLimiter,
            final SubjectDenialRateLimiter subjectRateLimiter, final Clock clock) {
        this.repository = repository;
        this.objectRateLimiter = objectRateLimiter;
        this.subjectRateLimiter = subjectRateLimiter;
        this.clock = clock;
    }

    public void record(final String subject, final String action, final long datasetId) {
        final Instant now = clock.instant();
        final ObjectDenialRateLimiter.Reservation objectReservation =
                objectRateLimiter.check(subject, datasetId, action, now);
        final SubjectDenialRateLimiter.Reservation subjectReservation;
        try {
            subjectReservation = subjectRateLimiter.check(subject, action, now);
        } catch (final RuntimeException ex) {
            objectRateLimiter.release(objectReservation);
            throw ex;
        }
        final long epochMinute = now.getEpochSecond() / ObjectDenialRateLimiter.WINDOW_SECONDS;
        final String reasonCode = CatalogErrorCodes.tailOf(ErrorCodes.RESOURCE_NOT_FOUND);
        final String dedupKey = subject.length() + ":" + subject + "|" + datasetId + "|" + action
                + "|" + reasonCode + "|" + epochMinute;
        try {
            repository.insertDeniedLog(new DatasetActionLog(null, subject, null, datasetId, action,
                    null, null, ActionResult.DENIED, reasonCode,
                    LocalDateTime.ofInstant(now, clock.getZone())), dedupKey);
        } catch (final RuntimeException ex) {
            subjectRateLimiter.release(subjectReservation);
            objectRateLimiter.release(objectReservation);
            throw ex;
        }
    }
}
