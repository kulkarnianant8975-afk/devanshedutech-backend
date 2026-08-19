package com.devanshedutech.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class DemoDTOs {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BookDemoRequest {
        private String leadId;
        private LocalDateTime scheduledAt;
        private String mode;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MarkDemoRequest {
        private Boolean attended;
        private String feedback;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DemoResponse {
        private String id;
        private String leadId;
        private String studentName;
        private String course;
        private LocalDateTime scheduledAt;
        private String mode;
        /** Null until somebody marks it — not yet known is not the same as did not attend. */
        private Boolean attended;
        private String feedback;
        /** The time has passed and nobody has said what happened. */
        private boolean awaitingMarking;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DemoBoardResponse {
        private LocalDate from;
        private LocalDate to;
        private List<DemoResponse> demos;
        /** Past demos nobody has marked, whenever they were. These are where the data rots. */
        private List<DemoResponse> awaitingMarking;
        private int scheduled;
        private long attended;
        /** Null until at least one demo in the window has been marked. */
        private Double attendanceRate;
    }
}
