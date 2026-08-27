package com.devanshedutech.model;

import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.model.crm.LeadSource;
import com.devanshedutech.model.crm.LostReason;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.model.crm.StudentBackground;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An enquiry, from first contact to enrolment or loss.
 *
 * <p>Everything above the CRM block is the original website-form record. Everything below it
 * exists because the Counsellor SOP requires it: a grade, a stage, a source, an owner, and —
 * above all — a next touch. The SOP's golden rule is that an active lead must never have a
 * blank next touch, because a blank next touch is how leads die.</p>
 *
 * <p>Columns are only ever added here, never renamed or dropped, because the schema is managed
 * by Hibernate's {@code ddl-auto: update}, which cannot perform destructive migrations.</p>
 */
@Entity
@Table(name = "leads", indexes = {
    @Index(name = "idx_lead_email", columnList = "email"),
    @Index(name = "idx_lead_status", columnList = "status"),
    @Index(name = "idx_lead_course", columnList = "course_interested"),
    @Index(name = "idx_lead_stage", columnList = "stage"),
    @Index(name = "idx_lead_grade", columnList = "grade"),
    @Index(name = "idx_lead_owner", columnList = "assigned_to_id"),
    @Index(name = "idx_lead_next_touch", columnList = "next_touch_on"),
    @Index(name = "idx_lead_phone_norm", columnList = "phone_normalized")
})
@org.hibernate.annotations.SQLRestriction("deleted_at is null")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lead {

    @Id
    private String id;

    @Column(name = "full_name")
    private String fullName;

    private String email;

    @Column(name = "mobile_number")
    private String mobileNumber;

    /** Free-text education as typed on the website form. Superseded by {@link #background}. */
    private String education;

    @Column(name = "city_name")
    private String cityName;

    /**
     * Free-text course name as captured. Stays free text until the course catalogue is
     * reconciled; {@link #courseId} links to the catalogue once a match is known.
     */
    @Column(name = "course_interested")
    private String courseInterested;

    /**
     * Legacy free-text status. Retained only so existing rows can be migrated into
     * {@link #stage}; no business logic reads it.
     *
     * @deprecated use {@link #stage}.
     */
    @Deprecated
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ------------------------------------------------------------------
    // CRM
    // ------------------------------------------------------------------

    /** Digits-only phone key used to spot the same student enquiring twice. */
    @Column(name = "phone_normalized", length = 20)
    private String phoneNormalized;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 24)
    private Stage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", length = 8)
    private Grade grade;

    /** When the lead entered its current grade, for history and for reporting. */
    @Column(name = "grade_entered_at")
    private LocalDateTime gradeEnteredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 24)
    private LeadSource source;

    /** Detail behind the source: the seminar's college, the ad's name, who referred them. */
    @Column(name = "source_detail")
    private String sourceDetail;

    @Enumerated(EnumType.STRING)
    @Column(name = "background", length = 16)
    private StudentBackground background;

    @Column(name = "course_id")
    private String courseId;

    @Column(name = "assigned_to_id")
    private String assignedToId;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    // --- the golden rule ---

    @Column(name = "next_touch_on")
    private LocalDate nextTouchOn;

    @Column(name = "next_touch_note")
    private String nextTouchNote;

    @Column(name = "last_touch_at")
    private LocalDateTime lastTouchAt;

    @Column(name = "last_touch_note")
    private String lastTouchNote;

    /** When a human first replied. The basis of the playbook's five-minute metric. */
    @Column(name = "first_responded_at")
    private LocalDateTime firstRespondedAt;

    /** Last message received from the student. */
    @Column(name = "last_inbound_at")
    private LocalDateTime lastInboundAt;

    /** Unanswered calls in a row. The SOP stops calling after three and continues on WhatsApp. */
    @Column(name = "call_attempts")
    private Integer callAttempts;

    // --- follow-up ladder ---

    /** Position in the current grade's seven-step ladder, 1-based. */
    @Column(name = "ladder_step")
    private Integer ladderStep;

    /** Manual freeze — exams, a holiday, a gap between intakes. */
    @Column(name = "ladder_paused_until")
    private LocalDate ladderPausedUntil;

    @Column(name = "ladder_pause_reason")
    private String ladderPauseReason;

    /**
     * True when the lead decayed all the way to lost without a counsellor ever really working
     * it. Kept distinct from a genuine rejection, because otherwise poor follow-up hides inside
     * the loss numbers — which is the exact failure this whole system exists to stop.
     */
    @Column(name = "lost_unworked")
    private Boolean lostUnworked;

    // --- closing ---

    @Enumerated(EnumType.STRING)
    @Column(name = "lost_reason", length = 32)
    private LostReason lostReason;

    @Column(name = "lost_note")
    private String lostNote;

    /** Day-21 wrap-ups and lost leads: announcements only, no manual chasing. Never deleted. */
    @Column(name = "updates_only")
    private Boolean updatesOnly;

    /** Student asked to stop. Excluded from every follow-up and broadcast, permanently. */
    @Column(name = "opted_out")
    private Boolean optedOut;

    @Column(name = "opted_out_at")
    private LocalDateTime optedOutAt;

    // --- enrolment ---

    /** The batch this student joined, once they enrol. */
    @Column(name = "batch_id")
    private String batchId;

    /** What they agreed to pay, as a counsellor would write it: "45,000 in three instalments". */
    @Column(name = "fee_plan")
    private String feePlan;

    /** PENDING, PART_PAID or PAID. Deliberately coarse: this is not an accounting system. */
    @Column(name = "payment_status", length = 20)
    private String paymentStatus;

    @Column(name = "enrolled_at")
    private LocalDateTime enrolledAt;

    /** The enrolled student who referred them, if any. */
    @Column(name = "referred_by_id")
    private String referredById;

    // --- attribution ---

    @Column(name = "utm_source")
    private String utmSource;

    @Column(name = "utm_medium")
    private String utmMedium;

    @Column(name = "utm_campaign")
    private String utmCampaign;

    @Column(name = "referrer_url", length = 1000)
    private String referrerUrl;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * When this lead was deleted, or null while it is live.
     *
     * <p>Deleting used to remove the row. One mis-click took a student's name, their number and
     * every note anybody had written about them, with no undo — on records that exist precisely
     * because they are hard to acquire.</p>
     *
     * <p>The {@code @SQLRestriction} above keeps deleted rows out of every query written against
     * this entity, so nothing has to remember to exclude them. Reading them back is deliberately
     * awkward: it takes a native query, which is the recycle bin and nothing else.</p>
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by_id")
    private String deletedById;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (stage == null) stage = Stage.NEW;
        if (callAttempts == null) callAttempts = 0;
        if (ladderStep == null) ladderStep = 1;
        if (lostUnworked == null) lostUnworked = false;
        if (updatesOnly == null) updatesOnly = false;
        if (optedOut == null) optedOut = false;
        if (phoneNormalized == null) phoneNormalized = normalizePhone(mobileNumber);
        updatedAt = createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
        if (phoneNormalized == null) phoneNormalized = normalizePhone(mobileNumber);
    }

    /**
     * Reduces a typed number to its last ten digits, so "+91 98765 43210", "098765 43210" and
     * "9876543210" all collapse to one key. Deliberately lenient: this is a matching hint for
     * the counsellor, not a validation gate on the public enquiry form.
     */
    public static String normalizePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    /** True while the lead is still being worked: not enrolled, not lost, not opted out. */
    public boolean isActive() {
        return stage != null && !stage.isClosed() && !Boolean.TRUE.equals(optedOut);
    }

    /** True while the follow-up ladder is frozen by hand. */
    public boolean isLadderPaused(LocalDate on) {
        return ladderPausedUntil != null && ladderPausedUntil.isAfter(on);
    }

    /** The SOP violation the end-of-day check looks for. Cold leads are exempt by design. */
    public boolean hasBlankNextTouch() {
        return isActive() && nextTouchOn == null && grade != Grade.COLD;
    }
}
