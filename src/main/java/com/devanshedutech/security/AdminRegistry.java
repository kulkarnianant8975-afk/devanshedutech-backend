package com.devanshedutech.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for which accounts are administrators.
 *
 * This logic previously existed as a copy-pasted block with hardcoded email literals in
 * both CustomUserDetailsService and CustomOAuth2UserService, so granting or revoking an
 * admin required a code change, and the two copies could silently disagree.
 *
 * Configured via app.admin.emails (comma-separated).
 */
@Component
public class AdminRegistry {

    private final Set<String> adminEmails;

    public AdminRegistry(@Value("${app.admin.emails:}") String configuredEmails) {
        this.adminEmails = configuredEmails == null || configuredEmails.isBlank()
                ? Set.of()
                : Arrays.stream(configuredEmails.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(String email) {
        return email != null && adminEmails.contains(email.trim().toLowerCase(Locale.ROOT));
    }

    /** Resolves the effective role, promoting configured admins regardless of the stored value. */
    public String resolveRole(String email, String storedRole) {
        if (isAdmin(email)) {
            return "ADMIN";
        }
        return storedRole == null || storedRole.isBlank() ? "USER" : storedRole.toUpperCase(Locale.ROOT);
    }
}
