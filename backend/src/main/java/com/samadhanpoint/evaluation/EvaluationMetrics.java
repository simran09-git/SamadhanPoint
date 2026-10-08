package com.samadhanpoint.evaluation;

import java.util.*;

/** Pure, dependency-free metrics used by the BIT-16 evaluation dossier. */
public final class EvaluationMetrics {
    private EvaluationMetrics() {}

    public static double accuracy(List<String> expected, List<String> predicted) {
        if (expected == null || predicted == null || expected.isEmpty() || expected.size() != predicted.size()) return 0.0;
        long correct = 0;
        for (int i = 0; i < expected.size(); i++) if (Objects.equals(expected.get(i), predicted.get(i))) correct++;
        return (double) correct / expected.size();
    }

    public static double macroF1(List<String> expected, List<String> predicted, Set<String> labels) {
        if (expected == null || predicted == null || expected.size() != predicted.size() || labels == null || labels.isEmpty()) return 0.0;
        double sum = 0; int used = 0;
        for (String label : labels) {
            int tp=0, fp=0, fn=0;
            for (int i=0;i<expected.size();i++) {
                boolean e=Objects.equals(label,expected.get(i)), p=Objects.equals(label,predicted.get(i));
                if(e&&p) tp++; else if(!e&&p) fp++; else if(e) fn++;
            }
            double precision=(tp+fp)==0?0:(double)tp/(tp+fp);
            double recall=(tp+fn)==0?0:(double)tp/(tp+fn);
            if (precision+recall>0) { sum += 2*precision*recall/(precision+recall); used++; }
        }
        return used==0?0:sum/used;
    }
}
