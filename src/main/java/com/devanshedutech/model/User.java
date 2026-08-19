package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A person who can sign in. Staff members hold a {@link Role}; anyone else holds
 * {@link Role#NONE} and can sign in but do nothing.
 *
 * <p>Accounts are never deleted, only deactivated. The activity log and audit trail reference
 * user ids, and removing the row would leave history attributed to nobody — so
 * {@link #active} is the off switch and ids are never reused.</p>
 *
 * <p>{@code role} stays a plain string column because rows written before the CRM hold values
 * like "user" and "admin"; mapping it to an enum type directly would fail to load them. Read it
 * through {@link #role()} instead of touching the raw string.</p>
 */
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_email", columnList = "email"),
    @Index(name = "idx_user_role", columnList = "role"),
    @Index(name = "idx_user_active", columnList = "active")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    private String id;

    @Column(unique = true, nullable = false)
    private String email;

    private String password;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "photo_url", columnDefinition = "TEXT")
    private String photoUrl;

    /** Stored as the {@link Role} name. Read via {@link #role()}, which tolerates legacy values. */
    private String role;

    // ------------------------------------------------------------------
    // Staff record
    // ------------------------------------------------------------------

    /** Deactivated accounts keep their history but cannot sign in or hold leads. */
    private Boolean active;

    /** Used for the duty roster and for calling a colleague about a lead. */
    private String phone;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** Who created this account, when it was created by a manager rather than self-registered. */
    @Column(name = "created_by_id")
    private String createdById;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (active == null) active = true;
        if (role == null || role.isBlank()) role = Role.NONE.name();
    }

    /** The effective role as stored. Configured overrides are applied separately by the registry. */
    public Role role() {
        return Role.parse(role);
    }

    public void setRole(Role r) {
        this.role = (r == null ? Role.NONE : r).name();
    }

    /**
     * Raw setter kept for the legacy string form. Both overloads are declared explicitly so
     * Lombok does not generate a third; pass {@code null} through the enum overload only.
     */
    public void setRole(String r) {
        this.role = r;
    }

    public boolean isActive() {
        return active == null || active;
    }

    /** Name for timelines and assignment dropdowns; never blank. */
    public String displayNameOrEmail() {
        return displayName == null || displayName.isBlank() ? email : displayName;
    }
}
