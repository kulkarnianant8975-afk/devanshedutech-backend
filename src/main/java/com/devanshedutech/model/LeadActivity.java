package com.devanshedutech.model;

import com.devanshedutech.model.crm.ActivityType;
import com.devanshedutech.model.crm.Direction;
import com.devanshedutech.model.crm.OutcomeCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One entry in a lead's history: a call, a WhatsApp, a stage change, a note.
 *
 * <p>Append-only by convention — nothing in the application updates or deletes rows here. The
 * lead's "last touch" is derived from this table rather than being a column a counsellor has to
 * remember to maintain, which is what the SOP means by "if it's not written in the pipeline, it
 * didn't happen".</p>
 */
@Entity
@Table(name = "lead_activities", indexes = {
    @Index(name = "idx_activity_lead", columnList = "lead_id"),
    @Index(name = "idx_activity_created", columnList = "created_at"),
    @Index(name = "idx_activity_type", columnList = "type")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadActivity {

    @Id
    private String id;

    @Column(name = "lead_id", nullable = false)
    private String leadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false)
    private ActivityType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 24)
    private OutcomeCode outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 10)
    private Direction direction;

    /**
     * The stage this activity moved the lead into, for stage-change entries.
     *
     * <p>Recorded as a column rather than left inside the human-readable detail text, because
     * conversion rates are computed from it: "enquiry to demo" must count every lead that ever
     * reached a demo, including those that later went cold, and that history only exists here.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage_to", length = 24)
    private com.devanshedutech.model.crm.Stage stageTo;

    /** One-line headline, e.g. "Call — no answer". */
    @Column(name = "summary", nullable = false)
    private String summary;

    /** What was actually said or sent. */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    /** Which pack was sent, when this entry is an outbound send. */
    @Column(name = "pack_key", length = 40)
    private String packKey;

    /** queued / sent / delivered / failed, once a messaging provider reports back. */
    @Column(name = "delivery_status", length = 20)
    private String deliveryStatus;

    @Column(name = "created_by_id")
    private String createdById;

    /** Denormalised so the timeline still reads correctly if a counsellor account is removed. */
    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /** True for entries the system wrote itself, used to separate real contact from bookkeeping. */
    public boolean isSystemGenerated() {
        return type == ActivityType.SYSTEM || type == ActivityType.STAGE_CHANGE
                || type == ActivityType.GRADE_CHANGE || type == ActivityType.ASSIGNMENT;
    }
}
