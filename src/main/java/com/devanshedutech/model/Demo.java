package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A booked demo class or campus visit.
 *
 * <p>The SOP treats the demo as the hinge of the whole process — "the best way to judge is to
 * feel it yourself" — and section 6.7 says the day-one and day-three follow-ups afterwards are
 * where enrolments are won or lost. Recording attendance is also what makes the enquiry-to-demo
 * and demo-to-enrolment rates measurable rather than guessed.</p>
 */
@Entity
@Table(name = "demos", indexes = {
    @Index(name = "idx_demo_lead", columnList = "lead_id"),
    @Index(name = "idx_demo_scheduled", columnList = "scheduled_at"),
    @Index(name = "idx_demo_attended", columnList = "attended")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Demo {

    @Id
    private String id;

    @Column(name = "lead_id", nullable = false)
    private String leadId;

    /** Denormalised so the calendar reads correctly without loading every lead. */
    @Column(name = "student_name")
    private String studentName;

    @Column(name = "course")
    private String course;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    /** Demo class, campus visit, or online — whatever the institute actually offers. */
    @Column(name = "mode", length = 32)
    private String mode;

    /**
     * Null until the day has been marked. Deliberately three-state: not-yet-known is different
     * from did-not-turn-up, and collapsing them would quietly count every future demo as a
     * no-show in the attendance figures.
     */
    @Column(name = "attended")
    private Boolean attended;

    @Column(name = "marked_at")
    private LocalDateTime markedAt;

    @Column(name = "feedback", columnDefinition = "TEXT")
    private String feedback;

    @Column(name = "created_by_id")
    private String createdById;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public boolean isUpcoming() {
        return attended == null && scheduledAt != null && scheduledAt.isAfter(LocalDateTime.now());
    }

    /** A demo whose time has passed but which nobody has marked. These are the ones that rot. */
    public boolean isAwaitingMarking() {
        return attended == null && scheduledAt != null && scheduledAt.isBefore(LocalDateTime.now());
    }
}
