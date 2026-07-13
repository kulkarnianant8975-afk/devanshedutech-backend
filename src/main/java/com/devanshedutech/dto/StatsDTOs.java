package com.devanshedutech.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

public class StatsDTOs {
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StatsResponse {
        private long totalLeads;
        private long totalCourses;
        private long totalHiring;
        private long totalMentors;
        private long totalPlacedStudents;
        private List<MonthlyLead> monthlyLeads;
        private List<CourseLead> leadsByCourse;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MonthlyLead {
        private String name;
        private long leads;
    }

    /** Real lead counts per course. The dashboard's "Leads by course" chart was
     *  previously fed the monthly series, so it just repeated the chart above it. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CourseLead {
        private String name;
        private long leads;
    }
}
