package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * An append-only record of security-relevant actions: who did what, to whom, and when.
 *
 * <p>Kept separate from the lead activity timeline on purpose. That log is a sales record a
 * counsellor reads; this one is an access record a manager audits, and mixing them would make
 * both harder to trust.</p>
 */
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_actor", columnList = "actor_id"),
    @Index(name = "idx_audit_created", columnList = "created_at"),
    @Index(name = "idx_audit_action", columnList = "action")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    private String id;

    @Column(name = "actor_id")
    private String actorId;

    /** Denormalised so the record still reads correctly if the account is later renamed. */
    @Column(name = "actor_email")
    private String actorEmail;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "target_type", length = 40)
    private String targetType;

    @Column(name = "target_id")
    private String targetId;

    @Column(columnDefinition = "TEXT")
    private String detail;

    /** Recorded for sign-in events; useful when an account is suspected of being shared. */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
