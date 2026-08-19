package com.devanshedutech.model;

import java.util.Locale;

/**
 * Staff roles for the CRM.
 *
 * <p>The five staff tiers are as specified for the project. {@link #NONE} is deliberately not a
 * sixth tier — it is the absence of one, and exists because the application previously allowed
 * open self-registration with a free-text role of "USER". Mapping those legacy accounts onto
 * {@link #VIEWER} would silently hand every person who ever signed up read access to the whole
 * pipeline, so they land on NONE instead and a manager grants a real role deliberately.</p>
 *
 * <p>The stored column remains a plain string so existing rows keep loading; {@link #parse} is
 * tolerant of the casing and legacy values already in the database.</p>
 */
public enum Role {

    /** Everything, including granting admin roles and destructive operations. */
    SUPER_ADMIN("Super Admin", 100),

    /** Runs the institute's system: staff, configuration, website content, the whole pipeline. */
    ADMIN("Admin", 80),

    /** Sees every lead, reassigns work, reads the team scorecard. Cannot change system settings. */
    MANAGER("Manager", 60),

    /**
     * Works their own leads only. Called a Counsellor everywhere a human reads it, which is the
     * word the SOP and the team actually use; the enum name follows the project specification.
     */
    SALES_EXECUTIVE("Counsellor", 40),

    /** Read-only across the pipeline and reports. Cannot change anything. */
    VIEWER("Viewer", 20),

    /** Not a staff account. No access to anything beyond signing in. */
    NONE("No access", 0);

    private final String label;
    private final int rank;

    Role(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    public String getLabel() { return label; }

    /** Higher rank outranks lower. Used to stop staff editing accounts above their own level. */
    public int getRank() { return rank; }

    public boolean outranks(Role other) {
        return other == null || this.rank > other.rank;
    }

    public boolean atLeast(Role other) {
        return other != null && this.rank >= other.rank;
    }

    /** True for roles that work the pipeline at all. */
    public boolean isStaff() {
        return this != NONE;
    }

    /**
     * Reads whatever is in the database. Unknown and legacy values resolve to {@link #NONE}
     * rather than to a permissive default — an unrecognised role must never mean "allow".
     */
    public static Role parse(String raw) {
        if (raw == null || raw.isBlank()) return NONE;
        String k = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (Role r : values()) {
            if (r.name().equals(k)) return r;
        }
        return switch (k) {
            case "COUNSELLOR", "COUNSELOR", "SALES", "EXECUTIVE", "SALES_EXEC" -> SALES_EXECUTIVE;
            case "SUPERADMIN", "SUPER" -> SUPER_ADMIN;
            // "USER" is the legacy self-registration value: an account, but not a staff member.
            default -> NONE;
        };
    }
}
