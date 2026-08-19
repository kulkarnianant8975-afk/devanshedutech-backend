package com.devanshedutech.security;

import com.devanshedutech.model.Permission;
import com.devanshedutech.model.Role;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.devanshedutech.model.Permission.*;

/**
 * The one place that says what each role may do.
 *
 * <p>Deliberately a static table rather than database-backed permissions: at this size an
 * editable permission matrix is a way to lock yourself out, not a feature. If per-user
 * overrides are ever needed, they belong beside this table, not instead of it.</p>
 *
 * <p>Two rules are encoded here on purpose and are worth stating plainly. An Admin cannot grant
 * Admin or Super Admin, so nobody can quietly promote themselves or a colleague past the
 * owner's intent. And a Viewer holds no write permission at all, so read-only really is
 * read-only rather than a UI that merely hides the buttons.</p>
 */
public final class RolePermissions {

    private static final Map<Role, Set<Permission>> TABLE = Map.of(

            Role.SUPER_ADMIN, EnumSet.allOf(Permission.class),

            Role.ADMIN, EnumSet.of(
                    USER_VIEW, USER_MANAGE, ROLE_ASSIGN,
                    LEAD_VIEW_OWN, LEAD_VIEW_ALL, LEAD_CREATE, LEAD_EDIT, LEAD_ASSIGN, LEAD_DELETE,
                    REPORT_VIEW, REPORT_VIEW_TEAM,
                    CONTENT_MANAGE, SETTINGS_MANAGE, AUDIT_VIEW),

            Role.MANAGER, EnumSet.of(
                    USER_VIEW,
                    LEAD_VIEW_OWN, LEAD_VIEW_ALL, LEAD_CREATE, LEAD_EDIT, LEAD_ASSIGN,
                    REPORT_VIEW, REPORT_VIEW_TEAM),

            Role.SALES_EXECUTIVE, EnumSet.of(
                    LEAD_VIEW_OWN, LEAD_CREATE, LEAD_EDIT,
                    REPORT_VIEW),

            Role.VIEWER, EnumSet.of(
                    LEAD_VIEW_ALL,
                    REPORT_VIEW),

            Role.NONE, EnumSet.noneOf(Permission.class)
    );

    private RolePermissions() {}

    public static Set<Permission> of(Role role) {
        return TABLE.getOrDefault(role == null ? Role.NONE : role, Set.of());
    }

    public static boolean has(Role role, Permission permission) {
        return of(role).contains(permission);
    }

    /** Authority strings handed to Spring Security, e.g. {@code PERM_LEAD_VIEW_ALL}. */
    public static Set<String> authorities(Role role) {
        Set<String> out = new java.util.LinkedHashSet<>();
        out.add("ROLE_" + (role == null ? Role.NONE : role).name());
        for (Permission p : of(role)) {
            out.add("PERM_" + p.name());
        }
        return out;
    }

    /**
     * Whether {@code actor} may set someone's role to {@code target}.
     *
     * <p>Requires the right permission for the tier being granted, and refuses to grant a role
     * the actor does not themselves outrank or equal — so a Manager cannot mint an Admin even
     * if a permission were mistakenly added to their row.</p>
     */
    public static boolean canGrant(Role actor, Role target) {
        if (actor == null || target == null) return false;
        boolean privileged = target == Role.ADMIN || target == Role.SUPER_ADMIN;
        if (privileged) {
            return has(actor, ROLE_ASSIGN_ADMIN);
        }
        return has(actor, ROLE_ASSIGN) && actor.atLeast(target);
    }
}
