package com.samadhanpoint.evaluation;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

/** Pure SLA policy used by the application and unit-tested for reproducible BIT-16 evidence. */
public final class SlaPolicy {
    private static final Set<String> CLOSED = Set.of("RESOLVED","COMPLETED","CLOSED");
    private SlaPolicy() {}
    public static long remainingMinutes(LocalDateTime created, int allowedHours, LocalDateTime now) {
        if (created == null || allowedHours <= 0) return 0;
        return Math.max(0, (long)allowedHours * 60L - Math.max(0, Duration.between(created, now).toMinutes()));
    }
    public static boolean breached(LocalDateTime created, LocalDateTime resolved, String status, int allowedHours, LocalDateTime now) {
        if (created == null || allowedHours <= 0) return false;
        LocalDateTime deadline=created.plusHours(allowedHours);
        if (resolved != null) return resolved.isAfter(deadline);
        if (CLOSED.contains(status)) return false;
        return now.isAfter(deadline);
    }
}
