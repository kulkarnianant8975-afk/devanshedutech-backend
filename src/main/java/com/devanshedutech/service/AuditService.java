package com.devanshedutech.service;

import com.devanshedutech.model.AuditLog;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Writes the security audit trail.
 *
 * <p>Failures here are logged and swallowed. An audit write must never be the reason a sign-in
 * or a role change fails for a real user — but a gap in the trail is worth shouting about in
 * the application log, so it is not silent either.</p>
 */
@Slf4j
@Service
public class AuditService {

    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String ROLE_CHANGED = "ROLE_CHANGED";
    public static final String USER_DEACTIVATED = "USER_DEACTIVATED";
    public static final String USER_REACTIVATED = "USER_REACTIVATED";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(User actor, String action, String targetType, String targetId,
                       String detail, HttpServletRequest request) {
        try {
            repository.save(AuditLog.builder()
                    .id(UUID.randomUUID().toString())
                    .actorId(actor == null ? null : actor.getId())
                    .actorEmail(actor == null ? null : actor.getEmail())
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(detail)
                    .ipAddress(clientIp(request))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (RuntimeException e) {
            log.error("Audit write failed for action {} — the action itself still succeeded", action, e);
        }
    }

    public void record(User actor, String action, String detail, HttpServletRequest request) {
        record(actor, action, null, null, detail, request);
    }

    /** Records an email rather than a user, for events where no account resolved. */
    public void recordAnonymous(String email, String action, String detail, HttpServletRequest request) {
        try {
            repository.save(AuditLog.builder()
                    .id(UUID.randomUUID().toString())
                    .actorEmail(email)
                    .action(action)
                    .detail(detail)
                    .ipAddress(clientIp(request))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (RuntimeException e) {
            log.error("Audit write failed for action {}", action, e);
        }
    }

    /**
     * Best-effort client address. The application sits behind a reverse proxy, so the first
     * entry of X-Forwarded-For is preferred; it is spoofable and is recorded as a hint for a
     * human reading the trail, never as an authorisation input.
     */
    private String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            return first.length() > 64 ? first.substring(0, 64) : first;
        }
        return request.getRemoteAddr();
    }
}
