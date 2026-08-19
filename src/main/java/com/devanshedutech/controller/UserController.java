package com.devanshedutech.controller;

import com.devanshedutech.dto.UserDTOs.ActiveChangeRequest;
import com.devanshedutech.dto.UserDTOs.AuditEntryResponse;
import com.devanshedutech.dto.UserDTOs.CreateUserRequest;
import com.devanshedutech.dto.UserDTOs.PasswordResetRequest;
import com.devanshedutech.dto.UserDTOs.RoleChangeRequest;
import com.devanshedutech.dto.UserDTOs.RoleOption;
import com.devanshedutech.dto.UserDTOs.TeamResponse;
import com.devanshedutech.dto.UserDTOs.UpdateUserRequest;
import com.devanshedutech.dto.UserDTOs.UserResponse;
import com.devanshedutech.model.AuditLog;
import com.devanshedutech.model.Permission;
import com.devanshedutech.model.Role;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.AuditLogRepository;
import com.devanshedutech.repository.UserRepository;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.security.AdminRegistry;
import com.devanshedutech.security.RolePermissions;
import com.devanshedutech.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Staff administration: who works here, what they may do, and who changed it.
 *
 * <p>Every method authorises server-side through {@link AccessService} rather than relying on
 * the client hiding a button. The guards below exist because the obvious failure mode of a
 * roles screen is locking the institute out of its own CRM: nobody may change their own role,
 * deactivate themselves, or act on somebody who outranks them, and the last active admin cannot
 * be removed.</p>
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final AccessService access;
    private final AuditService audit;
    private final AdminRegistry registry;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository,
                          AuditLogRepository auditLogRepository,
                          AccessService access,
                          AuditService audit,
                          AdminRegistry registry,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.access = access;
        this.audit = audit;
        this.registry = registry;
        this.passwordEncoder = passwordEncoder;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_USER_VIEW')")
    public ResponseEntity<TeamResponse> team(Authentication auth) {
        access.require(auth, Permission.USER_VIEW);
        Role actorRole = access.roleOf(auth);

        List<UserResponse> users = userRepository.findAllByOrderByDisplayNameAsc().stream()
                .map(this::toResponse)
                .toList();

        List<RoleOption> roles = Arrays.stream(Role.values())
                .map(r -> RoleOption.builder()
                        .value(r)
                        .label(r.getLabel())
                        .description(describe(r))
                        .grantable(RolePermissions.canGrant(actorRole, r))
                        .build())
                .toList();

        return ResponseEntity.ok(new TeamResponse(users, roles));
    }

    /** Staff who can own a lead — used by the assignment controls from Feature 2 onward. */
    @GetMapping("/assignable")
    @PreAuthorize("hasAuthority('PERM_USER_VIEW')")
    public ResponseEntity<List<UserResponse>> assignable(Authentication auth) {
        access.require(auth, Permission.USER_VIEW);
        return ResponseEntity.ok(userRepository.findAssignableStaff().stream()
                .map(this::toResponse).toList());
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('PERM_AUDIT_VIEW')")
    public ResponseEntity<List<AuditEntryResponse>> auditTrail(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        access.require(auth, Permission.AUDIT_VIEW);
        int capped = Math.min(Math.max(size, 1), 500);
        return ResponseEntity.ok(auditLogRepository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(page, 0), capped))
                .map(this::toResponse).getContent());
    }

    // ------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_USER_MANAGE')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request,
                                               Authentication auth,
                                               HttpServletRequest http) {
        access.require(auth, Permission.USER_MANAGE);
        User actor = access.requireUser(auth);
        Role actorRole = access.roleOf(auth);
        Role target = request.getRole() == null ? Role.SALES_EXECUTIVE : request.getRole();

        if (!RolePermissions.canGrant(actorRole, target)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You cannot create a " + target.getLabel() + " account.");
        }
        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account already exists for " + email + ".");
        }

        User user = User.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .displayName(request.getDisplayName().trim())
                .phone(request.getPhone())
                .active(true)
                .createdAt(LocalDateTime.now())
                .createdById(actor.getId())
                .build();
        user.setRole(target);
        userRepository.save(user);

        audit.record(actor, AuditService.USER_CREATED, "USER", user.getId(),
                "Created " + email + " as " + target.getLabel(), http);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(user));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_USER_MANAGE')")
    public ResponseEntity<UserResponse> update(@PathVariable String id,
                                               @RequestBody UpdateUserRequest request,
                                               Authentication auth,
                                               HttpServletRequest http) {
        access.require(auth, Permission.USER_MANAGE);
        User actor = access.requireUser(auth);
        User target = find(id);
        requireOutranks(access.roleOf(auth), target, actor, "edit");

        if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
            target.setDisplayName(request.getDisplayName().trim());
        }
        if (request.getPhone() != null) {
            target.setPhone(request.getPhone().trim());
        }
        userRepository.save(target);

        audit.record(actor, AuditService.USER_UPDATED, "USER", target.getId(),
                "Updated profile of " + target.getEmail(), http);
        return ResponseEntity.ok(toResponse(target));
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasAuthority('PERM_ROLE_ASSIGN')")
    public ResponseEntity<UserResponse> changeRole(@PathVariable String id,
                                                   @RequestBody RoleChangeRequest request,
                                                   Authentication auth,
                                                   HttpServletRequest http) {
        User actor = access.requireUser(auth);
        Role actorRole = access.roleOf(auth);
        User target = find(id);
        Role newRole = request.getRole();

        if (newRole == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a role.");
        }
        if (target.getId().equals(actor.getId())) {
            // Self-promotion is the first thing anyone tries; it is also how an institute ends
            // up with no administrator when someone demotes themselves by accident.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You cannot change your own role. Ask another administrator.");
        }
        requireOutranks(actorRole, target, actor, "change the role of");
        if (!RolePermissions.canGrant(actorRole, newRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You cannot grant the " + newRole.getLabel() + " role.");
        }
        Role previous = target.role();
        if (previous.atLeast(Role.ADMIN) && !newRole.atLeast(Role.ADMIN)) {
            requireAnotherAdminRemains(target);
        }

        target.setRole(newRole);
        userRepository.save(target);

        String pinnedWarning = registry.isPinned(target.getEmail())
                ? " (note: this account's role is pinned in configuration and will revert on next sign-in)"
                : "";
        audit.record(actor, AuditService.ROLE_CHANGED, "USER", target.getId(),
                target.getEmail() + ": " + previous.getLabel() + " → " + newRole.getLabel()
                        + (request.getReason() == null ? "" : " — " + request.getReason())
                        + pinnedWarning, http);
        return ResponseEntity.ok(toResponse(target));
    }

    @PatchMapping("/{id}/active")
    @PreAuthorize("hasAuthority('PERM_USER_MANAGE')")
    public ResponseEntity<UserResponse> setActive(@PathVariable String id,
                                                  @RequestBody ActiveChangeRequest request,
                                                  Authentication auth,
                                                  HttpServletRequest http) {
        access.require(auth, Permission.USER_MANAGE);
        User actor = access.requireUser(auth);
        User target = find(id);
        boolean activate = Boolean.TRUE.equals(request.getActive());

        if (target.getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You cannot deactivate your own account.");
        }
        requireOutranks(access.roleOf(auth), target, actor, activate ? "reactivate" : "deactivate");
        if (!activate && target.role().atLeast(Role.ADMIN)) {
            requireAnotherAdminRemains(target);
        }

        target.setActive(activate);
        target.setDeactivatedAt(activate ? null : LocalDateTime.now());
        userRepository.save(target);

        audit.record(actor, activate ? AuditService.USER_REACTIVATED : AuditService.USER_DEACTIVATED,
                "USER", target.getId(),
                target.getEmail() + (request.getReason() == null ? "" : " — " + request.getReason()), http);
        return ResponseEntity.ok(toResponse(target));
    }

    @PostMapping("/{id}/password")
    @PreAuthorize("hasAuthority('PERM_USER_MANAGE')")
    public ResponseEntity<Void> resetPassword(@PathVariable String id,
                                              @Valid @RequestBody PasswordResetRequest request,
                                              Authentication auth,
                                              HttpServletRequest http) {
        access.require(auth, Permission.USER_MANAGE);
        User actor = access.requireUser(auth);
        User target = find(id);
        requireOutranks(access.roleOf(auth), target, actor, "reset the password of");

        target.setPassword(passwordEncoder.encode(request.getPassword()));
        userRepository.save(target);

        // The new password is never written to the audit trail or the application log.
        audit.record(actor, AuditService.PASSWORD_RESET, "USER", target.getId(),
                "Password reset for " + target.getEmail(), http);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    private User find(String id) {
        return userRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That account no longer exists."));
    }

    /** Nobody may act on an account at or above their own level, except on themselves. */
    private void requireOutranks(Role actorRole, User target, User actor, String verb) {
        if (target.getId().equals(actor.getId())) return;
        if (!actorRole.outranks(target.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You cannot " + verb + " an account at the " + target.role().getLabel() + " level.");
        }
    }

    /**
     * Refuses a change that would leave the institute with no active administrator. Counts
     * configured admins too, since those accounts can always sign back in.
     */
    private void requireAnotherAdminRemains(User excluding) {
        boolean another = userRepository.findAll().stream()
                .filter(u -> !u.getId().equals(excluding.getId()))
                .filter(User::isActive)
                .anyMatch(u -> registry.resolve(u.getEmail(), u.getRole()).atLeast(Role.ADMIN));
        if (!another) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This is the last active administrator. Promote someone else first.");
        }
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private UserResponse toResponse(User u) {
        Role effective = registry.resolve(u.getEmail(), u.getRole());
        return UserResponse.builder()
                .id(u.getId())
                .email(u.getEmail())
                .displayName(u.displayNameOrEmail())
                .photoURL(u.getPhotoUrl())
                .phone(u.getPhone())
                .role(effective)
                .roleLabel(effective.getLabel())
                .active(u.isActive())
                .roleLockedByConfig(registry.isPinned(u.getEmail()))
                .createdAt(u.getCreatedAt())
                .lastLoginAt(u.getLastLoginAt())
                .build();
    }

    private AuditEntryResponse toResponse(AuditLog a) {
        return AuditEntryResponse.builder()
                .id(a.getId())
                .actorEmail(a.getActorEmail())
                .action(a.getAction())
                .targetType(a.getTargetType())
                .targetId(a.getTargetId())
                .detail(a.getDetail())
                .ipAddress(a.getIpAddress())
                .createdAt(a.getCreatedAt())
                .build();
    }

    private String describe(Role r) {
        return switch (r) {
            case SUPER_ADMIN -> "Everything, including granting admin roles.";
            case ADMIN -> "Staff, configuration, website content and the whole pipeline.";
            case MANAGER -> "Sees every lead, reassigns work, reads the team scorecard.";
            case SALES_EXECUTIVE -> "Works their own leads. The counsellor role.";
            case VIEWER -> "Read-only across the pipeline and reports.";
            case NONE -> "Can sign in but has no access. Legacy sign-ups land here.";
        };
    }
}
