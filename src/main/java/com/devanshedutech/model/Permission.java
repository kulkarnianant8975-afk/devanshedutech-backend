package com.devanshedutech.model;

/**
 * What a role is allowed to do.
 *
 * <p>Endpoints authorise against these rather than against role names, so adding a role later
 * is a change to one table in {@code RolePermissions} instead of a sweep through every
 * controller. Granted to the security context as {@code PERM_*} authorities.</p>
 */
public enum Permission {

    /** See the staff list. */
    USER_VIEW,
    /** Create staff, edit their details, deactivate them. */
    USER_MANAGE,
    /** Grant or change roles up to Manager. */
    ROLE_ASSIGN,
    /** Grant Admin or Super Admin. Held only by Super Admin, so admins cannot promote themselves. */
    ROLE_ASSIGN_ADMIN,

    /** See leads assigned to yourself. */
    LEAD_VIEW_OWN,
    /** See every lead in the institute. */
    LEAD_VIEW_ALL,
    LEAD_CREATE,
    LEAD_EDIT,
    /** Assign or reassign a lead's owner. */
    LEAD_ASSIGN,
    /**
     * Hard-delete a lead. Held by admins only and, per SOP section 6.8, not used in normal
     * operation — a lead that goes nowhere is marked Lost and kept, because they may return.
     */
    LEAD_DELETE,

    /** Read dashboards and reports. */
    REPORT_VIEW,
    /** Read per-counsellor performance, not just your own numbers. */
    REPORT_VIEW_TEAM,

    /** Manage courses, mentors, success stories, hiring posts and site content. */
    CONTENT_MANAGE,
    /** Change system settings and integration configuration. */
    SETTINGS_MANAGE,
    /** Read the audit trail. */
    AUDIT_VIEW
}
