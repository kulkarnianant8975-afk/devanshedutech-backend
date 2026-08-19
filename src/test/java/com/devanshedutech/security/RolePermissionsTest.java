package com.devanshedutech.security;

import com.devanshedutech.model.Permission;
import com.devanshedutech.model.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RolePermissionsTest {

    @Test
    @DisplayName("every role has a row, so a missing entry can never mean unrestricted")
    void everyRoleIsCovered() {
        for (Role r : Role.values()) {
            assertNotNull(RolePermissions.of(r), r + " has no permission set");
        }
    }

    @Test
    void noAccessRoleHasNothing() {
        assertTrue(RolePermissions.of(Role.NONE).isEmpty());
        assertTrue(RolePermissions.of(null).isEmpty(), "an unresolved role must grant nothing");
    }

    @Test
    void superAdminHasEverything() {
        assertEquals(Permission.values().length, RolePermissions.of(Role.SUPER_ADMIN).size());
    }

    @Test
    @DisplayName("a viewer is genuinely read-only, not a hidden button")
    void viewerCannotWrite() {
        var viewer = RolePermissions.of(Role.VIEWER);
        assertFalse(viewer.contains(Permission.LEAD_EDIT));
        assertFalse(viewer.contains(Permission.LEAD_CREATE));
        assertFalse(viewer.contains(Permission.LEAD_ASSIGN));
        assertFalse(viewer.contains(Permission.USER_MANAGE));
        assertFalse(viewer.contains(Permission.CONTENT_MANAGE));
        assertTrue(viewer.contains(Permission.LEAD_VIEW_ALL));
        assertTrue(viewer.contains(Permission.REPORT_VIEW));
    }

    @Test
    @DisplayName("a counsellor sees only their own leads and no team reporting")
    void counsellorIsScopedToOwnWork() {
        var exec = RolePermissions.of(Role.SALES_EXECUTIVE);
        assertTrue(exec.contains(Permission.LEAD_VIEW_OWN));
        assertFalse(exec.contains(Permission.LEAD_VIEW_ALL));
        assertFalse(exec.contains(Permission.LEAD_ASSIGN));
        assertFalse(exec.contains(Permission.REPORT_VIEW_TEAM));
        assertFalse(exec.contains(Permission.USER_VIEW));
    }

    @Test
    @DisplayName("a manager runs the pipeline but cannot change the system")
    void managerCannotAdminister() {
        var mgr = RolePermissions.of(Role.MANAGER);
        assertTrue(mgr.contains(Permission.LEAD_VIEW_ALL));
        assertTrue(mgr.contains(Permission.LEAD_ASSIGN));
        assertTrue(mgr.contains(Permission.REPORT_VIEW_TEAM));
        assertFalse(mgr.contains(Permission.SETTINGS_MANAGE));
        assertFalse(mgr.contains(Permission.CONTENT_MANAGE));
        assertFalse(mgr.contains(Permission.USER_MANAGE));
        assertFalse(mgr.contains(Permission.LEAD_DELETE));
    }

    @Test
    @DisplayName("only a super admin can mint admins")
    void adminCannotEscalate() {
        assertFalse(RolePermissions.canGrant(Role.ADMIN, Role.ADMIN));
        assertFalse(RolePermissions.canGrant(Role.ADMIN, Role.SUPER_ADMIN));
        assertTrue(RolePermissions.canGrant(Role.SUPER_ADMIN, Role.ADMIN));
        assertTrue(RolePermissions.canGrant(Role.SUPER_ADMIN, Role.SUPER_ADMIN));

        assertTrue(RolePermissions.canGrant(Role.ADMIN, Role.MANAGER));
        assertTrue(RolePermissions.canGrant(Role.ADMIN, Role.SALES_EXECUTIVE));
    }

    @Test
    @DisplayName("roles without the assign permission cannot grant anything")
    void unprivilegedRolesCannotGrant() {
        for (Role target : Role.values()) {
            assertFalse(RolePermissions.canGrant(Role.MANAGER, target),
                    "manager must not grant " + target);
            assertFalse(RolePermissions.canGrant(Role.SALES_EXECUTIVE, target));
            assertFalse(RolePermissions.canGrant(Role.VIEWER, target));
            assertFalse(RolePermissions.canGrant(Role.NONE, target));
        }
    }

    @Test
    void nullsCannotGrant() {
        assertFalse(RolePermissions.canGrant(null, Role.VIEWER));
        assertFalse(RolePermissions.canGrant(Role.SUPER_ADMIN, null));
    }

    @Test
    @DisplayName("authorities carry both the role and its permissions")
    void authoritiesIncludeRoleAndPermissions() {
        var auths = RolePermissions.authorities(Role.MANAGER);
        assertTrue(auths.contains("ROLE_MANAGER"));
        assertTrue(auths.contains("PERM_LEAD_VIEW_ALL"));
        assertFalse(auths.contains("PERM_SETTINGS_MANAGE"));

        assertEquals(java.util.Set.of("ROLE_NONE"), RolePermissions.authorities(Role.NONE));
    }
}
