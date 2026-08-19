package com.devanshedutech.repository;

import com.devanshedutech.model.AuditLog;
import com.devanshedutech.model.Role;
import com.devanshedutech.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes the staff and audit queries against a database.
 *
 * <p>{@code findAssignableStaff} filters on role names written as string literals inside JPQL,
 * which is exactly the kind of query that compiles cleanly and then returns the wrong rows —
 * or none — the first time it runs.</p>
 */
@DataJpaTest
class UserRepositoryQueryTest {

    @Autowired private UserRepository users;
    @Autowired private AuditLogRepository auditLogs;

    private User save(String email, Role role, boolean active) {
        User u = User.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .displayName(email.split("@")[0])
                .active(active)
                .createdAt(LocalDateTime.now())
                .build();
        u.setRole(role);
        return users.save(u);
    }

    @BeforeEach
    void seed() {
        users.deleteAll();
        save("owner@x.com", Role.SUPER_ADMIN, true);
        save("admin@x.com", Role.ADMIN, true);
        save("manager@x.com", Role.MANAGER, true);
        save("sneha@x.com", Role.SALES_EXECUTIVE, true);
        save("aditya@x.com", Role.SALES_EXECUTIVE, false);   // deactivated
        save("viewer@x.com", Role.VIEWER, true);
        save("public@x.com", Role.NONE, true);               // legacy self-registration
    }

    @Test
    @DisplayName("only active staff who can act on a lead are assignable")
    void assignableStaffExcludesTheRest() {
        List<String> emails = users.findAssignableStaff().stream().map(User::getEmail).toList();

        assertTrue(emails.contains("sneha@x.com"));
        assertTrue(emails.contains("manager@x.com"));
        assertTrue(emails.contains("admin@x.com"));
        assertTrue(emails.contains("owner@x.com"));

        assertFalse(emails.contains("aditya@x.com"), "a deactivated account cannot hold leads");
        assertFalse(emails.contains("viewer@x.com"), "a viewer is read-only and cannot own work");
        assertFalse(emails.contains("public@x.com"), "a legacy sign-up is not staff");
        assertEquals(4, emails.size());
    }

    @Test
    @DisplayName("the role column round-trips as a canonical name")
    void roleRoundTrips() {
        User found = users.findByEmailIgnoreCase("SNEHA@X.COM").orElseThrow();
        assertEquals(Role.SALES_EXECUTIVE, found.role());
        assertEquals("SALES_EXECUTIVE", found.getRole());
    }

    @Test
    void emailLookupIgnoresCase() {
        assertTrue(users.findByEmailIgnoreCase("Admin@X.com").isPresent());
        assertTrue(users.findByEmailIgnoreCase("admin@x.com").isPresent());
        assertTrue(users.findByEmailIgnoreCase("nobody@x.com").isEmpty());
    }

    @Test
    @DisplayName("the audit trail persists and pages newest first")
    void auditTrailPages() {
        for (int i = 0; i < 5; i++) {
            auditLogs.save(AuditLog.builder()
                    .id(UUID.randomUUID().toString())
                    .actorEmail("admin@x.com")
                    .action("ROLE_CHANGED")
                    .targetType("USER")
                    .targetId("t" + i)
                    .detail("change " + i)
                    .createdAt(LocalDateTime.now().plusSeconds(i))
                    .build());
        }
        var page = auditLogs.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 3));
        assertEquals(3, page.getContent().size());
        assertEquals(5, page.getTotalElements());
        assertEquals("change 4", page.getContent().get(0).getDetail());

        assertEquals(1, auditLogs.findByTargetTypeAndTargetIdOrderByCreatedAtDesc("USER", "t0").size());
        assertEquals(0, auditLogs.findByActorIdOrderByCreatedAtDesc("nobody").size());
    }
}
