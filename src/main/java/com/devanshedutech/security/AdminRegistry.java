package com.devanshedutech.security;

import com.devanshedutech.model.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for which accounts hold which role.
 *
 * <p>This logic previously existed as a copy-pasted block with hardcoded email literals in both
 * CustomUserDetailsService and CustomOAuth2UserService, so granting or revoking an admin
 * required a code change, and the two copies could silently disagree.</p>
 *
 * <p>Configured roles override whatever is stored on the user row, and the highest configured
 * role wins. That is the bootstrap path: an institute owner listed in {@code app.admin.emails}
 * gets admin on their first Google sign-in, before any account exists to grant it to them.
 * Day-to-day role changes are made in the Team screen and stored on the user; configuration is
 * for the accounts that must never be lockable-out.</p>
 */
@Component
public class AdminRegistry {

    private final Map<Role, Set<String>> configured = new LinkedHashMap<>();

    public AdminRegistry(@Value("${app.super-admin.emails:}") String superAdmins,
                         @Value("${app.admin.emails:}") String admins,
                         @Value("${app.manager.emails:}") String managers,
                         @Value("${app.counsellor.emails:}") String counsellors,
                         @Value("${app.viewer.emails:}") String viewers) {
        // Insertion order matters: the first match wins, so the most privileged list is first.
        configured.put(Role.SUPER_ADMIN, parse(superAdmins));
        configured.put(Role.ADMIN, parse(admins));
        configured.put(Role.MANAGER, parse(managers));
        configured.put(Role.SALES_EXECUTIVE, parse(counsellors));
        configured.put(Role.VIEWER, parse(viewers));
    }

    private static Set<String> parse(String csv) {
        return csv == null || csv.isBlank()
                ? Set.of()
                : Arrays.stream(csv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    private static String key(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** The role this email is pinned to by configuration, or null when it is not pinned. */
    public Role configuredRole(String email) {
        String k = key(email);
        if (k == null) return null;
        for (Map.Entry<Role, Set<String>> e : configured.entrySet()) {
            if (e.getValue().contains(k)) return e.getKey();
        }
        return null;
    }

    /**
     * Resolves the effective role. Configuration wins over the stored value; otherwise the
     * stored value is used, and anything unrecognised resolves to {@link Role#NONE} rather than
     * to a permissive default.
     */
    public Role resolve(String email, String storedRole) {
        Role pinned = configuredRole(email);
        if (pinned != null) return pinned;
        return Role.parse(storedRole);
    }

    /** Kept for callers that still work in strings; always returns an uppercase role name. */
    public String resolveRole(String email, String storedRole) {
        return resolve(email, storedRole).name();
    }

    public boolean isAdmin(String email) {
        Role r = configuredRole(email);
        return r == Role.ADMIN || r == Role.SUPER_ADMIN;
    }

    /** True when a role change would be overwritten on next sign-in by configuration. */
    public boolean isPinned(String email) {
        return configuredRole(email) != null;
    }
}
