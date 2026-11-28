package com.ctds.catalog.application;

import com.ctds.catalog.domain.CatalogRateLimitException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class SubjectDenialRateLimiter {

    static final int WINDOW_SECONDS = 60;

    private final Clock clock;
    private final int maxRequestsPerWindow;
    private final int maxTrackedKeys;
    private final LinkedHashMap<DenialKey, WindowCount> windows =
            new LinkedHashMap<>(16, 0.75f, true);

    public SubjectDenialRateLimiter(final Clock clock, final int maxRequestsPerWindow,
            final int maxTrackedKeys) {
        if (maxRequestsPerWindow < 1 || maxTrackedKeys < 1) {
            throw new IllegalArgumentException("主体拒绝限流阈值与跟踪容量必须大于 0");
        }
        this.clock = clock;
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.maxTrackedKeys = maxTrackedKeys;
    }

    public synchronized Reservation check(final String subject, final String action) {
        return check(subject, action, clock.instant());
    }

    synchronized Reservation check(final String subject, final String action, final Instant now) {
        final long window = now.getEpochSecond() / WINDOW_SECONDS;
        final DenialKey key = new DenialKey(subject, action);
        WindowCount state = windows.get(key);
        if (state == null || state.window != window) {
            state = new WindowCount(window);
            windows.put(key, state);
            evictOverflow();
        }
        if (state.count >= maxRequestsPerWindow) {
            throw new CatalogRateLimitException(retryAfterSeconds(now));
        }
        state.count++;
        return new Reservation(key, state);
    }

    public synchronized void release(final Reservation reservation) {
        final WindowCount current = windows.get(reservation.key);
        if (current == reservation.state && current.count > 0) {
            current.count--;
        }
    }

    private void evictOverflow() {
        while (windows.size() > maxTrackedKeys) {
            final Map.Entry<DenialKey, WindowCount> eldest = windows.entrySet().iterator().next();
            windows.remove(eldest.getKey());
        }
    }

    private static int retryAfterSeconds(final Instant now) {
        return WINDOW_SECONDS - (int) (now.getEpochSecond() % WINDOW_SECONDS);
    }

    public static final class Reservation {
        private final DenialKey key;
        private final WindowCount state;

        private Reservation(final DenialKey key, final WindowCount state) {
            this.key = key;
            this.state = state;
        }
    }

    private record DenialKey(String subject, String action) {
    }

    private static final class WindowCount {
        private final long window;
        private int count;

        private WindowCount(final long window) {
            this.window = window;
        }
    }
}
