package com.devanshedutech.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

public class MetricsDTOs {

    /**
     * One of the playbook's six numbers. Carries its own target and direction so the client
     * renders what "healthy" means rather than hardcoding a judgement of its own.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Metric {
        private String key;
        private String label;
        /** Null when there is not enough data to compute it honestly. */
        private Double value;
        private String unit;
        private String target;
        /** What the number means, in the words a manager would use. */
        private String explanation;
        /** True when the number is currently in the healthy range. Null when unknown. */
        private Boolean healthy;
        /** How many records the figure is based on, so a lucky sample is visible as one. */
        private long sampleSize;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FunnelStep {
        private String stage;
        private String label;
        private long reached;
        /** Percentage of all enquiries that ever reached this stage. */
        private double percentOfTotal;
        /** Percentage lost between the previous stage and this one; null for the first. */
        private Double dropFromPrevious;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SourcePerformance {
        private String source;
        private String label;
        private long leads;
        private long enrolled;
        private Double conversionRate;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WeeklyCount {
        private LocalDate weekStarting;
        private long leads;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CounsellorScore {
        private String userId;
        private String name;
        private long activeLeads;
        private long enrolled;
        private Double conversionRate;
        private long overdueTouches;
        private long blankNextTouch;
        /** Leads that decayed to lost without ever really being worked. */
        private long lostUnworked;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PipelineMetricsResponse {
        private List<Metric> metrics;
        private List<FunnelStep> funnel;
        private List<SourcePerformance> sources;
        private List<WeeklyCount> weekly;
        private List<CounsellorScore> counsellors;
        private long totalLeads;
        private String windowDescription;
    }
}
