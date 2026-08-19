package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * An in-app notification for one member of staff.
 *
 * <p>Notifications carry a {@link #dedupeKey} so the same fact cannot be announced twice. That
 * matters more here than it sounds: the daily pass runs every morning over the same overdue
 * leads, and without a key a counsellor would arrive to fifty copies of a notice they had
 * already read, at which point they stop reading notifications altogether.</p>
 */
@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notif_recipient", columnList = "recipient_id"),
    @Index(name = "idx_notif_read", columnList = "read_at"),
    @Index(name = "idx_notif_dedupe", columnList = "dedupe_key"),
    @Index(name = "idx_notif_created", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    private String id;

    @Column(name = "recipient_id", nullable = false)
    private String recipientId;

    /** LEAD_ASSIGNED, FOLLOW_UP_DUE, FOLLOW_UP_MISSED, DEMO_TOMORROW, DEMO_UNMARKED, ENROLLED. */
    @Column(nullable = false, length = 40)
    private String kind;

    @Column(nullable = false)
    private String title;

    @Column(length = 1000)
    private String body;

    /** The lead this concerns, so clicking the notice opens the right screen. */
    @Column(name = "lead_id")
    private String leadId;

    /**
     * Identifies the fact being announced, not the announcement. One row per key per recipient,
     * so re-running the daily pass cannot produce duplicates.
     */
    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public boolean isRead() { return readAt != null; }
}
