package com.ctds.catalog.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ctds.catalog.denial-protection")
public class CatalogDenialProperties {

    private int maxRequestsPerMinute = 20;
    private int maxTrackedKeys = 10000;
    private int maxSubjectRequestsPerMinute = 100;
    private int maxSubjectTrackedKeys = 10000;

    public int getMaxRequestsPerMinute() {
        return maxRequestsPerMinute;
    }

    public void setMaxRequestsPerMinute(final int maxRequestsPerMinute) {
        if (maxRequestsPerMinute < 1) {
            throw new IllegalArgumentException("ctds.catalog.denial-protection.max-requests-per-minute 必须大于 0");
        }
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }

    public int getMaxTrackedKeys() {
        return maxTrackedKeys;
    }

    public void setMaxTrackedKeys(final int maxTrackedKeys) {
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException("ctds.catalog.denial-protection.max-tracked-keys 必须大于 0");
        }
        this.maxTrackedKeys = maxTrackedKeys;
    }

    public int getMaxSubjectRequestsPerMinute() {
        return maxSubjectRequestsPerMinute;
    }

    public void setMaxSubjectRequestsPerMinute(final int maxSubjectRequestsPerMinute) {
        if (maxSubjectRequestsPerMinute < 1) {
            throw new IllegalArgumentException(
                    "ctds.catalog.denial-protection.max-subject-requests-per-minute 必须大于 0");
        }
        this.maxSubjectRequestsPerMinute = maxSubjectRequestsPerMinute;
    }

    public int getMaxSubjectTrackedKeys() {
        return maxSubjectTrackedKeys;
    }

    public void setMaxSubjectTrackedKeys(final int maxSubjectTrackedKeys) {
        if (maxSubjectTrackedKeys < 1) {
            throw new IllegalArgumentException(
                    "ctds.catalog.denial-protection.max-subject-tracked-keys 必须大于 0");
        }
        this.maxSubjectTrackedKeys = maxSubjectTrackedKeys;
    }
}
