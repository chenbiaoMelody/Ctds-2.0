package com.ctds.catalog.domain;

public class CatalogRateLimitException extends CatalogBizException {

    private final int retryAfterSeconds;

    public CatalogRateLimitException(final int retryAfterSeconds) {
        super(CatalogErrorCodes.OBJECT_ACCESS_RATE_LIMITED,
                CatalogErrorCodes.OBJECT_ACCESS_RATE_LIMITED_MESSAGE);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
