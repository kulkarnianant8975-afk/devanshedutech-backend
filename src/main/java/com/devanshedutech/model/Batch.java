package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A dated intake of one course.
 *
 * <p>The follow-up ladder tells counsellors to "share the batch start date" on day twelve and
 * "next batch starts on…" on day eighteen, and until now there was nowhere to look that up. The
 * playbook also plans the marketing push six to eight weeks before an intake, which needs the
 * intake to exist as a date rather than as something in somebody's head.</p>
 */
@Entity
@Table(name = "batches", indexes = {
    @Index(name = "idx_batch_course", columnList = "course_id"),
    @Index(name = "idx_batch_start", columnList = "start_date"),
    @Index(name = "idx_batch_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Batch {

    @Id
    private String id;

    @Column(name = "course_id", nullable = false)
    private String courseId;

    /** Denormalised so a batch list reads without joining every course. */
    @Column(name = "course_name")
    private String courseName;

    /** What a counsellor would say on the phone: "the September morning batch". */
    @Column(nullable = false)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Free text, because institutes describe timings in their own way. */
    @Column(name = "timing")
    private String timing;

    /**
     * Seats available. Null means uncapped rather than zero — an institute that has not decided
     * a limit should not have its batch reported as full.
     */
    private Integer capacity;

    /** PLANNED, OPEN, RUNNING or CLOSED. */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "PLANNED";
    }

    /** Whether a counsellor can still put somebody in it. */
    public boolean isTakingEnrolments() {
        return ("OPEN".equals(status) || "PLANNED".equals(status))
                && startDate != null && !startDate.isBefore(LocalDate.now());
    }

    /** How it should be described to a student, without a counsellor having to compose it. */
    public String describe() {
        StringBuilder s = new StringBuilder(name);
        if (startDate != null) s.append(", starting ").append(startDate);
        if (timing != null && !timing.isBlank()) s.append(", ").append(timing);
        return s.toString();
    }
}
