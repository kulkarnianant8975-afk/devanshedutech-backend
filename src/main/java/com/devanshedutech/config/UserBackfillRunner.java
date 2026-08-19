package com.devanshedutech.config;

import com.devanshedutech.model.Role;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.UserRepository;
import com.devanshedutech.security.AdminRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Normalises accounts that predate the role model.
 *
 * <p>Existing rows hold free-text roles such as "user", "USER" and "admin", and no active flag.
 * Each row is only touched where a value is missing or unparseable, so this is safe to run on
 * every boot and does nothing once complete.</p>
 *
 * <p>The important decision here is that legacy "USER" accounts become {@link Role#NONE}, not
 * {@link Role#VIEWER}. Self-registration used to be open, so those rows are members of the
 * public, not staff — promoting them to a read-only staff role would hand the entire pipeline
 * to everyone who ever signed up.</p>
 */
@Slf4j
@Configuration
public class UserBackfillRunner {

    @Bean
    @Order(10)
    public ApplicationRunner backfillUsers(UserRepository userRepository, AdminRegistry registry) {
        return args -> {
            List<User> all = userRepository.findAll();
            int touched = 0;
            int demoted = 0;

            for (User u : all) {
                boolean changed = false;

                if (u.getActive() == null) { u.setActive(true); changed = true; }
                if (u.getCreatedAt() == null) { u.setCreatedAt(LocalDateTime.now()); changed = true; }

                Role stored = Role.parse(u.getRole());
                Role pinned = registry.configuredRole(u.getEmail());
                Role effective = pinned != null ? pinned : stored;

                // Rewrite the column so it always holds a canonical role name.
                if (!effective.name().equals(u.getRole())) {
                    if (stored == Role.NONE && pinned == null
                            && u.getRole() != null && !u.getRole().isBlank()) {
                        demoted++;
                    }
                    u.setRole(effective);
                    changed = true;
                }

                if (changed) { userRepository.save(u); touched++; }
            }

            if (touched > 0) {
                log.info("Normalised {} of {} user accounts.", touched, all.size());
            }
            if (demoted > 0) {
                log.warn("{} account(s) had an unrecognised role and now hold NONE (no access). "
                        + "Grant real roles in the Team screen.", demoted);
            }

            long staff = all.stream()
                    .map(u -> registry.resolve(u.getEmail(), u.getRole()))
                    .filter(Role::isStaff).count();
            long admins = all.stream()
                    .filter(User::isActive)
                    .map(u -> registry.resolve(u.getEmail(), u.getRole()))
                    .filter(r -> r.atLeast(Role.ADMIN)).count();

            log.info("Team: {} staff account(s), {} active administrator(s).", staff, admins);
            if (admins == 0) {
                log.warn("No active administrator. Sign in with an address listed in "
                        + "app.admin.emails to bootstrap access.");
            }
        };
    }
}
