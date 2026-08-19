package com.devanshedutech.security;

import com.devanshedutech.model.Permission;
import com.devanshedutech.model.Role;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Answers "who is calling, and what may they see?" for every CRM endpoint.
 *
 * <p>Resolving the caller is less obvious than it looks: a form login puts a Spring
 * {@code UserDetails} in the security context while a Google sign-in puts an
 * {@code OAuth2User} there, and the existing controllers each re-implemented that branch. It is
 * implemented once here.</p>
 *
 * <p>{@link #ownerFilter(Authentication)} is the ownership primitive the whole CRM leans on: it
 * returns null for anyone allowed to see every lead, and the caller's own user id for anyone
 * who is not. A counsellor whose account cannot be resolved is restricted to an id that matches
 * nothing, so a lookup failure denies access rather than granting it.</p>
 */
@Service
public class AccessService {

    /** Sentinel owner id that no row can match, used when the caller cannot be identified. */
    private static final String NO_MATCH = "__unresolved_user__";

    private final UserRepository userRepository;
    private final AdminRegistry registry;

    public AccessService(UserRepository userRepository, AdminRegistry registry) {
        this.userRepository = userRepository;
        this.registry = registry;
    }

    public String emailOf(Authentication auth) {
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails u) return u.getUsername();
        if (principal instanceof OAuth2User o) {
            Object email = o.getAttribute("email");
            if (email != null) return email.toString();
        }
        return auth.getName();
    }

    public Optional<User> userOf(Authentication auth) {
        String email = emailOf(auth);
        return email == null ? Optional.empty() : userRepository.findByEmailIgnoreCase(email);
    }

    /** The caller's account, or 401 if the session does not resolve to an active one. */
    public User requireUser(Authentication auth) {
        User user = userOf(auth).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to continue."));
        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "This account has been deactivated.");
        }
        return user;
    }

    /**
     * The caller's effective role, or {@link Role#NONE} if their account has been deactivated.
     *
     * <p>Marking a user disabled stops them signing in again, but it does not touch sessions
     * they already hold — without this check, deactivating someone would leave them working
     * normally until their session happened to expire. Checking here means the very next
     * request they make has no permissions at all.</p>
     */
    public Role roleOf(Authentication auth) {
        Optional<User> user = userOf(auth);
        if (user.isPresent() && !user.get().isActive()) {
            return Role.NONE;
        }
        String email = emailOf(auth);
        return registry.resolve(email, user.map(User::getRole).orElse(null));
    }

    public boolean can(Authentication auth, Permission permission) {
        return RolePermissions.has(roleOf(auth), permission);
    }

    /** Throws 403 unless the caller holds the permission. Server-side, always. */
    public void require(Authentication auth, Permission permission) {
        if (!can(auth, permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role does not allow this action.");
        }
    }

    /**
     * The owner id a lead query must be restricted to, or null when the caller sees everything.
     */
    public String ownerFilter(Authentication auth) {
        if (can(auth, Permission.LEAD_VIEW_ALL)) return null;
        if (!can(auth, Permission.LEAD_VIEW_OWN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role does not allow viewing leads.");
        }
        return userOf(auth).map(User::getId).orElse(NO_MATCH);
    }

    /** True when the caller may act on a record owned by {@code ownerId}. */
    public boolean ownsOrSeesAll(Authentication auth, String ownerId) {
        if (can(auth, Permission.LEAD_VIEW_ALL)) return true;
        return userOf(auth).map(u -> u.getId().equals(ownerId)).orElse(false);
    }

    public String nameOf(String userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(User::displayNameOrEmail).orElse(null);
    }
}
