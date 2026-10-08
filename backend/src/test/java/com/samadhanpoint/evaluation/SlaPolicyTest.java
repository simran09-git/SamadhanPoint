package com.samadhanpoint.evaluation;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class SlaPolicyTest {
    private final LocalDateTime created=LocalDateTime.of(2026,10,3,10,0);
    @Test void openComplaintShowsRemainingTime(){
        assertEquals(120, SlaPolicy.remainingMinutes(created,4,created.plusHours(2)));
        assertFalse(SlaPolicy.breached(created,null,"IN_PROGRESS",4,created.plusHours(2)));
    }
    @Test void openComplaintBreachesAfterDeadline(){
        assertTrue(SlaPolicy.breached(created,null,"IN_PROGRESS",4,created.plusHours(4).plusMinutes(1)));
    }
    @Test void resolvedAfterDeadlineIsRecordedAsBreach(){
        assertTrue(SlaPolicy.breached(created,created.plusHours(5),"RESOLVED",4,created.plusHours(6)));
    }
    @Test void closedOnTimeIsNotBreach(){
        assertFalse(SlaPolicy.breached(created,created.plusHours(3),"CLOSED",4,created.plusHours(6)));
    }
}
