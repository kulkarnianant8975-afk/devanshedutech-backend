package com.devanshedutech.controller;

import com.devanshedutech.dto.UserDTOs.ActiveChangeRequest;
import com.devanshedutech.dto.UserDTOs.RoleChangeRequest;
import com.devanshedutech.model.Role;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.AuditLogRepository;
import com.devanshedutech.repository.UserRepository;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.security.AdminRegistry;
import com.devanshedutech.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The rules that stop a roles screen from locking an institute out of its own CRM.
 *
 * <p>These are the cases a happy-path test never reaches: promoting yourself, demoting the last
 * administrator, and acting on somebody senior to you.</p>
 */
class UserControllerGuardsTest {

    private UserRepository users;
    private AccessService access;
    private AuditService audit;
    private UserController controller;
    private Authentication auth;

    private User actor;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        access = mock(AccessService.class);
        audit = mock(AuditService.class);
        auth = mock(Authentication.class);
        AuditLogRepository auditRepo = mock(AuditLogRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hashed");

        controller = new UserController(users, auditRepo, access, audit,
                new AdminRegistry("", "", "", "", ""), encoder);

        actor = user("actor", "admin@x.com", Role.ADMIN);
        when(access.requireUser(any())).thenReturn(actor);
        when(access.roleOf(any())).thenReturn(Role.ADMIN);
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private User user(String id, String email, Role role) {
        User u = User.builder().id(id).email(email).displayName(email).active(true).build();
        u.setRole(role);
        return u;
    }

    @Test
    @DisplayName("nobody can change their own role")
    void cannotChangeOwnRole() {
        when(users.findById("actor")).thenReturn(Optional.of(actor));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.changeRole("actor", new RoleChangeRequest(Role.SUPER_ADMIN, null), auth, null));

        assertEquals(403, e.getStatusCode().value());
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("an admin cannot grant admin, so nobody can promote a colleague past the owner")
    void adminCannotGrantAdmin() {
        User target = user("t1", "sneha@x.com", Role.SALES_EXECUTIVE);
        when(users.findById("t1")).thenReturn(Optional.of(target));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.changeRole("t1", new RoleChangeRequest(Role.ADMIN, null), auth, null));

        assertEquals(403, e.getStatusCode().value());
        assertEquals(Role.SALES_EXECUTIVE, target.role(), "role must be untouched after refusal");
    }

    @Test
    @DisplayName("you cannot act on somebody at or above your own level")
    void cannotActOnEqualOrSenior() {
        User peer = user("t2", "other-admin@x.com", Role.ADMIN);
        when(users.findById("t2")).thenReturn(Optional.of(peer));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.changeRole("t2", new RoleChangeRequest(Role.VIEWER, null), auth, null));
        assertEquals(403, e.getStatusCode().value());
    }

    @Test
    @DisplayName("the last active administrator cannot be demoted")
    void cannotDemoteLastAdmin() {
        User target = user("t3", "last-admin@x.com", Role.ADMIN);
        // The actor is a super admin here, so rank is not what blocks the change.
        when(access.roleOf(any())).thenReturn(Role.SUPER_ADMIN);
        when(users.findById("t3")).thenReturn(Optional.of(target));
        when(users.findAll()).thenReturn(List.of(target, user("x", "counsellor@x.com", Role.SALES_EXECUTIVE)));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.changeRole("t3", new RoleChangeRequest(Role.MANAGER, null), auth, null));

        assertEquals(409, e.getStatusCode().value());
        assertEquals(Role.ADMIN, target.role());
    }

    @Test
    @DisplayName("an admin can be demoted while another one remains")
    void canDemoteWhenAnotherAdminExists() {
        User target = user("t4", "admin-two@x.com", Role.ADMIN);
        when(access.roleOf(any())).thenReturn(Role.SUPER_ADMIN);
        when(users.findById("t4")).thenReturn(Optional.of(target));
        when(users.findAll()).thenReturn(List.of(target, user("keep", "admin-three@x.com", Role.ADMIN)));

        assertDoesNotThrow(() ->
                controller.changeRole("t4", new RoleChangeRequest(Role.MANAGER, "restructure"), auth, null));
        assertEquals(Role.MANAGER, target.role());
        verify(audit).record(any(), eq(AuditService.ROLE_CHANGED), eq("USER"), eq("t4"), any(), any());
    }

    @Test
    @DisplayName("a deactivated admin does not count as cover for demoting the last one")
    void deactivatedAdminDoesNotCount() {
        User target = user("t5", "admin-two@x.com", Role.ADMIN);
        User inactive = user("gone", "old-admin@x.com", Role.ADMIN);
        inactive.setActive(false);
        when(access.roleOf(any())).thenReturn(Role.SUPER_ADMIN);
        when(users.findById("t5")).thenReturn(Optional.of(target));
        when(users.findAll()).thenReturn(List.of(target, inactive));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.changeRole("t5", new RoleChangeRequest(Role.VIEWER, null), auth, null));
        assertEquals(409, e.getStatusCode().value());
    }

    @Test
    @DisplayName("nobody can deactivate their own account")
    void cannotDeactivateSelf() {
        when(users.findById("actor")).thenReturn(Optional.of(actor));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.setActive("actor", new ActiveChangeRequest(false, null), auth, null));
        assertEquals(403, e.getStatusCode().value());
        assertTrue(actor.isActive());
    }

    @Test
    @DisplayName("deactivating a counsellor is allowed and audited")
    void canDeactivateJuniorStaff() {
        User target = user("t6", "sneha@x.com", Role.SALES_EXECUTIVE);
        when(users.findById("t6")).thenReturn(Optional.of(target));

        assertDoesNotThrow(() ->
                controller.setActive("t6", new ActiveChangeRequest(false, "left the company"), auth, null));
        assertFalse(target.isActive());
        assertNotNull(target.getDeactivatedAt());
        verify(audit).record(any(), eq(AuditService.USER_DEACTIVATED), eq("USER"), eq("t6"), any(), any());
    }

    @Test
    @DisplayName("a missing account is a 404, not a silent success")
    void missingUserIs404() {
        when(users.findById("nope")).thenReturn(Optional.empty());
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.setActive("nope", new ActiveChangeRequest(true, null), auth, null));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    @DisplayName("a role change with no role is rejected before anything is written")
    void nullRoleIsRejected() {
        User target = user("t7", "sneha@x.com", Role.SALES_EXECUTIVE);
        when(users.findById("t7")).thenReturn(Optional.of(target));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.changeRole("t7", new RoleChangeRequest(null, null), auth, null));
        assertEquals(400, e.getStatusCode().value());
        verify(users, never()).save(any());
    }
}
