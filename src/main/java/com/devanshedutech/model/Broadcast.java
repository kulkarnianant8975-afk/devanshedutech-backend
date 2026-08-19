package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * An announcement sent to a segment of leads nobody is actively chasing.
 *
 * <p>This is where the Cold lane leads. The SOP is explicit that cold leads get no counsellor
 * time — batch announcements, workshop invites and placement results only — and that a lead
 * marked Lost is kept rather than deleted because students come back for the next intake. Both
 * of those groups need somewhere to be reached, or the follow-up ladder simply decays people
 * into silence.</p>
 */
@Entity
@Table(name = "broadcasts", indexes = {
    @Index(name = "idx_broadcast_created", columnList = "created_at"),
    @Index(name = "idx_broadcast_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Broadcast {

    @Id
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    /** Which group it goes to: COLD, UPDATES_ONLY, LOST, ENROLLED or ALL_INACTIVE. */
    @Column(nullable = false, length = 24)
    private String segment;

    /** DRAFT, SENDING, SENT or FAILED. */
    @Column(nullable = false, length = 16)
    private String status;

    /** How many the segment matched when it was sent. */
    @Column(name = "recipient_count")
    private Integer recipientCount;

    @Column(name = "sent_count")
    private Integer sentCount;

    @Column(name = "failed_count")
    private Integer failedCount;

    /** People excluded because they asked to stop. Recorded so the number is explainable. */
    @Column(name = "skipped_opted_out")
    private Integer skippedOptedOut;

    @Column(name = "created_by_id")
    private String createdById;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "DRAFT";
    }
}
