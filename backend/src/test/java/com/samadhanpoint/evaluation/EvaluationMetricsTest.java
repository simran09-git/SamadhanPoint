package com.samadhanpoint.evaluation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationMetricsTest {
    @Test void accuracyIsCalculatedCorrectly() {
        assertEquals(0.75, EvaluationMetrics.accuracy(List.of("A","B","A","C"), List.of("A","B","C","C")), 0.00001);
    }

    @Test void macroF1IsCalculatedAcrossLabels() {
        double f1 = EvaluationMetrics.macroF1(
                List.of("A","A","B","B"),
                List.of("A","B","B","B"),
                Set.of("A","B"));
        assertEquals(0.733333, f1, 0.00001);
    }

    @Test void mismatchedInputsReturnZero() {
        assertEquals(0.0, EvaluationMetrics.accuracy(List.of("A"), List.of()));
    }
}
