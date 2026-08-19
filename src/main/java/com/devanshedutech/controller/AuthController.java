package com.devanshedutech.controller;

import com.devanshedutech.dto.AuthDTOs.LoginRequest;
import com.devanshedutech.dto.AuthDTOs.RegisterRequest;
import com.devanshedutech.dto.AuthDTOs.UserResponse;
import com.devanshedutech.model.Role;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.UserRepository;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.security.AdminRegistry;
import com.devanshedutech.security.RolePermissions;
import com.devanshedutech.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminRegistry registry;
    private final AccessService access;
    private final AuditService audit;

    /**
     * Open sign-up is off by default. This is an internal CRM: accounts are created by a
     * manager in the Team screen. Administrators listed in configuration can always bootstrap
     * themselves through Google sign-in, so turning this off cannot lock the institute out.
     */
    @Value("${app.auth.self-registration-enabled:false}")
    private boolean selfRegistrationEnabled;

    public AuthController(AuthenticationManager authenticationManager,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          AdminRegistry registry,
                          AccessService access,
                          AuditService audit) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.registry = registry;
        this.access = access;
        this.audit = audit;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        String email = loginRequest.getEmail() == null ? "" : loginRequest.getEmail().trim();
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, loginRequest.getPassword())
            );
            SecurityContext sc = SecurityContextHolder.getContext();
            sc.setAuthentication(authentication);
            HttpSession session = request.getSession(true);
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, sc);

            User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);

            audit.record(user, AuditService.LOGIN, "Signed in with a password", request);
            return ResponseEntity.ok(mapToResponse(user));
        } catch (Exception e) {
            // The same message for a wrong password and an unknown address, so the endpoint
            // cannot be used to discover which email addresses have accounts.
            audit.recordAnonymous(email, AuditService.LOGIN_FAILED, "Failed password sign-in", request);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Incorrect email or password");
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest registerRequest, HttpServletRequest request) {
        if (!selfRegistrationEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Accounts are created by an administrator. Ask your manager to add you to the team.");
        }
        if (registerRequest.getEmail() == null || registerRequest.getEmail().isBlank()
                || registerRequest.getPassword() == null || registerRequest.getPassword().length() < 8) {
            return ResponseEntity.badRequest().body("Enter an email and a password of at least 8 characters.");
        }
        String email = registerRequest.getEmail().trim().toLowerCase();
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            return ResponseEntity.badRequest().body("User already exists");
        }

        User user = User.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .password(passwordEncoder.encode(registerRequest.getPassword()))
                .displayName(registerRequest.getDisplayName() != null && !registerRequest.getDisplayName().isBlank()
                        ? registerRequest.getDisplayName().trim()
                        : email.split("@")[0])
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
        // Self-registration never grants access. A manager assigns a real role afterwards.
        user.setRole(Role.NONE);
        userRepository.save(user);

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, registerRequest.getPassword())
        );
        SecurityContext sc = SecurityContextHolder.getContext();
        sc.setAuthentication(authentication);
        HttpSession session = request.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, sc);

        audit.record(user, AuditService.USER_CREATED, "USER", user.getId(),
                "Self-registered with no access", request);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(user));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getMe(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Not authenticated");
        }
        String email = access.emailOf(authentication);
        Optional<User> user = userRepository.findByEmailIgnoreCase(email);
        if (user.isPresent()) {
            return ResponseEntity.ok(mapToResponse(user.get()));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not found");
    }

    @PutMapping("/profile-picture")
    public ResponseEntity<?> updateProfilePicture(@RequestBody java.util.Map<String, String> payload,
                                                  Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Not authenticated");
        }
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(access.emailOf(authentication));
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setPhotoUrl(payload.get("photoUrl"));
            userRepository.save(user);
            return ResponseEntity.ok(mapToResponse(user));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not found");
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, Authentication authentication) {
        if (authentication != null) {
            userRepository.findByEmailIgnoreCase(access.emailOf(authentication))
                    .ifPresent(u -> audit.record(u, AuditService.LOGOUT, "Signed out", request));
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok().build();
    }

    /**
     * Resolves the effective role through the registry rather than trusting the stored string,
     * and ships the permission set so the client renders navigation from what the server
     * actually allows. The client is still never the authority — every endpoint re-checks.
     */
    private UserResponse mapToResponse(User user) {
        Role role = registry.resolve(user.getEmail(), user.getRole());
        Set<String> permissions = RolePermissions.of(role).stream()
                .map(Enum::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .displayName(user.displayNameOrEmail())
                .photoURL(user.getPhotoUrl())
                .role(role.name())
                .roleLabel(role.getLabel())
                .active(user.isActive())
                .permissions(permissions)
                .build();
    }
}
