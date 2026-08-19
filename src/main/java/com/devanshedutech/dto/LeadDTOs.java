package com.devanshedutech.dto;

import com.devanshedutech.model.crm.ActivityType;
import com.devanshedutech.model.crm.Direction;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.model.crm.LeadSource;
import com.devanshedutech.model.crm.LostReason;
import com.devanshedutech.model.crm.OutcomeCode;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.model.crm.StudentBackground;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class LeadDTOs {

    /**
     * Public capture payload. The original six fields are unchanged so the website forms keep
     * working; the attribution fields are optional and filled in by the site, the chatbot or an
     * integration. Without them the playbook's headline question — which source actually
     * produces admissions — cannot be answered at all.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeadRequest {
        private String fullName;
        private String email;
        private String mobileNumber;
        private String education;
        private String cityName;
        private String courseInterested;

        private String source;
        private String sourceDetail;
        private String background;
        private String utmSource;
        private String utmMedium;
        private String utmCampaign;
        private String referrerUrl;
        private String notes;
        private String referredById;
    }

    /** Kept so the existing frontend call keeps working; it now moves the stage. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeadStatusUpdate {
        private String status;
    }

    /** Partial update from the pipeline. Any field left null is left alone. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeadPatchRequest {
        private Grade grade;
        private Stage stage;
        private LeadSource source;
        private StudentBackground background;
        private String sourceDetail;
        private String assignedToId;
        private Boolean clearOwner;
        private LocalDate nextTouchOn;
        private String nextTouchNote;
        private String notes;
        private String courseInterested;
        private LostReason lostReason;
        private String lostNote;
        /** Why the change was made, recorded on the timeline. */
        private String reason;
    }

    /** Recording what happened on a contact — the most-used write in the CRM. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OutcomeRequest {
        private OutcomeCode outcome;
        private String note;
        private LostReason lostReason;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ActivityRequest {
        private ActivityType type;
        private Direction direction;
        private String summary;
        private String detail;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InboundRequest {
        private String text;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeadResponse {
        private String id;
        private String fullName;
        private String email;
        private String mobileNumber;
        private String education;
        private String cityName;
        private String courseInterested;
        private String status;          // legacy label, mirrors stage
        private LocalDateTime createdAt;

        private Stage stage;
        private String stageLabel;
        private Grade grade;
        private String gradeLabel;
        private LeadSource source;
        private String sourceLabel;
        private String sourceDetail;
        private StudentBackground background;
        private String backgroundLabel;

        private String assignedToId;
        private String assignedToName;

        private LocalDate nextTouchOn;
        private String nextTouchNote;
        private Integer daysOverdue;
        private boolean blankNextTouch;

        private LocalDateTime lastTouchAt;
        private String lastTouchNote;
        private LocalDateTime firstRespondedAt;
        private Long firstResponseMinutes;
        private LocalDateTime lastInboundAt;
        private Integer callAttempts;

        private Integer ladderStep;
        private Integer ladderTotal;
        private LocalDate ladderPausedUntil;
        private String ladderPauseReason;
        private String ladderCurrentTitle;

        private LostReason lostReason;
        private String lostNote;
        /** Set when a lead decayed to lost without ever really being worked. */
        private Boolean lostUnworked;
        private Boolean updatesOnly;
        private Boolean optedOut;
        private String notes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ActivityResponse {
        private String id;
        private ActivityType type;
        private String typeLabel;
        private OutcomeCode outcome;
        private String outcomeLabel;
        private Direction direction;
        private String summary;
        private String detail;
        private String createdByName;
        private LocalDateTime createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeadDetailResponse {
        private LeadResponse lead;
        private List<ActivityResponse> activities;
        /** The lead's whole lane, so a counsellor can see what is coming, not just what is due. */
        private List<LadderStepResponse> ladder;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LadderStepResponse {
        private String id;
        private Grade grade;
        private Integer stepNo;
        private Integer dayOffset;
        private String title;
        private String action;
        private Boolean autoSend;
        private Boolean active;
        /** True for steps this lead has already passed. Null outside a lead's context. */
        private Boolean reached;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PauseRequest {
        private LocalDate until;
        private String reason;
    }

    /** The counsellor's daily queues, assembled server-side so the client makes one call. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MyDayResponse {
        private List<LeadResponse> awaitingFirstReply;
        private List<LeadResponse> overdue;
        private List<LeadResponse> dueToday;
        private List<LeadResponse> blankNextTouch;
        private long awaitingCount;
        private long overdueCount;
        private long dueTodayCount;
        private long blankNextTouchCount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PageResponse<T> {
        private List<T> items;
        private long total;
        private int page;
        private int size;
        private int totalPages;
    }

    /** Result of a public capture, so the site knows a repeat enquiry was recognised. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CaptureResponse {
        private String id;
        private String fullName;
        private boolean duplicate;
        private String message;
    }

    /** Enum vocabularies, so the client never hardcodes a list that can drift from the server. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OptionsResponse {
        private List<Option> stages;
        private List<Option> grades;
        private List<Option> sources;
        private List<Option> backgrounds;
        private List<Option> outcomes;
        private List<Option> lostReasons;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Option {
        private String value;
        private String label;
        private String hint;
    }
}
