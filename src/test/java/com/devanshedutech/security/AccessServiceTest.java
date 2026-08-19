package com.devanshedutech.security;

import com.devanshedutech.model.Permission;
import com.devanshedutech.model.Role;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessServiceTest {

    private UserRepository users;
    private AccessService access;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        // No pinned roles, so the stored role is what counts.
        access = new AccessService(users, new AdminRegistry("", "", "", "", ""));
    }

    private Authentication authFor(String email) {
        var principal = org.springframework.security.core.userdetails.User
                .withUsername(email).password("x").authorities(List.of()).build();
        return new UsernamePasswordAuthenticationToken(principal, "x", List.of());
    }

    private User user(String id, String email, Role role) {
        User u = User.builder().id(id).email(email).active(true).build();
        u.setRole(role);
        return u;
    }

    @Test
    @DisplayName("a manager is not restricted to their own leads")
    void managerSeesEverything() {
        User u = user("u1", "mgr@x.com", Role.MANAGER);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(u));

        assertNull(access.ownerFilter(authFor("mgr@x.com")));
    }

    @Test
    @DisplayName("a counsellor is restricted to their own user id")
    void counsellorIsRestricted() {
        User u = user("u2", "sneha@x.com", Role.SALES_EXECUTIVE);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(u));

        assertEquals("u2", access.ownerFilter(authFor("sneha@x.com")));
    }

    @Test
    @DisplayName("an unresolvable account is restricted to a sentinel, never opened up")
    void unresolvedUserIsRestrictedNotOpened() {
        // The email resolves to no row. Falling back to null here would silently expose the
        // entire pipeline, so the filter must be an id that matches nothing instead.
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        AccessService pinned = new AccessService(users,
                new AdminRegistry("", "", "", "ghost@x.com", ""));

        String filter = pinned.ownerFilter(authFor("ghost@x.com"));
        assertNotNull(filter);
        assertNotEquals("", filter);
    }

    @Test
    @DisplayName("a role with no lead access is refused outright")
    void noAccessRoleIsForbidden() {
        User u = user("u3", "public@x.com", Role.NONE);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(u));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> access.ownerFilter(authFor("public@x.com")));
        assertEquals(403, e.getStatusCode().value());
    }

    @Test
    @DisplayName("configuration pins a role over whatever is stored")
    void configuredRoleWinsOverStored() {
        User stored = user("u4", "owner@x.com", Role.NONE);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(stored));
        AccessService pinnedAccess = new AccessService(users,
                new AdminRegistry("", "owner@x.com", "", "", ""));

        assertEquals(Role.ADMIN, pinnedAccess.roleOf(authFor("owner@x.com")));
        assertTrue(pinnedAccess.can(authFor("owner@x.com"), Permission.CONTENT_MANAGE));
    }

    @Test
    @DisplayName("pinning is case-insensitive, because email addresses are")
    void pinningIgnoresCase() {
        User stored = user("u5", "Owner@X.com", Role.NONE);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(stored));
        AccessService pinnedAccess = new AccessService(users,
                new AdminRegistry("", " OWNER@x.COM ", "", "", ""));

        assertEquals(Role.ADMIN, pinnedAccess.roleOf(authFor("Owner@X.com")));
    }

    @Test
    void requireThrowsForbiddenWithoutPermission() {
        User u = user("u6", "view@x.com", Role.VIEWER);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(u));

        assertDoesNotThrow(() -> access.require(authFor("view@x.com"), Permission.REPORT_VIEW));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> access.require(authFor("view@x.com"), Permission.LEAD_EDIT));
        assertEquals(403, e.getStatusCode().value());
    }

    @Test
    @DisplayName("an anonymous request resolves to no role at all")
    void nullAuthenticationHasNoRole() {
        assertEquals(Role.NONE, access.roleOf(null));
        assertFalse(access.can(null, Permission.LEAD_VIEW_ALL));
        assertNull(access.emailOf(null));
    }

    @Test
    void ownershipCheckRespectsViewAll() {
        User mgr = user("m1", "mgr@x.com", Role.MANAGER);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(mgr));
        assertTrue(access.ownsOrSeesAll(authFor("mgr@x.com"), "someone-else"));

        User exec = user("e1", "exec@x.com", Role.SALES_EXECUTIVE);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(exec));
        assertTrue(access.ownsOrSeesAll(authFor("exec@x.com"), "e1"));
        assertFalse(access.ownsOrSeesAll(authFor("exec@x.com"), "someone-else"));
    }

    @Test
    @DisplayName("a deactivated account loses access on its very next request")
    void deactivatedAccountLosesAccessImmediately() {
        // Spring marks the account disabled so it cannot sign in again, but a session it
        // already holds stays valid until it expires. Without this check, deactivating someone
        // would leave them working normally in the meantime.
        User u = user("u7", "gone@x.com", Role.ADMIN);
        u.setActive(false);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(u));

        assertEquals(Role.NONE, access.roleOf(authFor("gone@x.com")));
        assertFalse(access.can(authFor("gone@x.com"), Permission.CONTENT_MANAGE));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> access.requireUser(authFor("gone@x.com")));
        assertEquals(401, e.getStatusCode().value());
    }

    @Test
    @DisplayName("deactivation beats a role pinned in configuration")
    void deactivationBeatsConfiguredRole() {
        User u = user("u8", "owner@x.com", Role.NONE);
        u.setActive(false);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(u));
        AccessService pinnedAccess = new AccessService(users,
                new AdminRegistry("owner@x.com", "", "", "", ""));

        assertEquals(Role.NONE, pinnedAccess.roleOf(authFor("owner@x.com")));
    }
}
