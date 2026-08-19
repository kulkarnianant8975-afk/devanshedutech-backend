package com.devanshedutech.security;

import com.devanshedutech.model.Role;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final AdminRegistry adminRegistry;

    public CustomOAuth2UserService(UserRepository userRepository, AdminRegistry adminRegistry) {
        this.userRepository = userRepository;
        this.adminRegistry = adminRegistry;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");
        String picture = oAuth2User.getAttribute("picture");

        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("email_required"),
                    "Google did not return an email address for this account.");
        }

        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);

        if (user != null && !user.isActive()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("account_disabled"),
                    "This account has been deactivated.");
        }

        // Configuration pins a role; otherwise the stored role stands. The previous version
        // recomputed the role on every sign-in and wrote it back, which silently reset any role
        // a manager had assigned in the Team screen the next time that person logged in with
        // Google. Roles are now only written here when configuration actually dictates one.
        Role pinned = adminRegistry.configuredRole(email);
        Role effective = pinned != null ? pinned : (user == null ? Role.NONE : user.role());

        if (user == null) {
            user = User.builder()
                    .id(java.util.UUID.randomUUID().toString())
                    .email(email.toLowerCase())
                    .displayName(name)
                    .photoUrl(picture)
                    .active(true)
                    .createdAt(LocalDateTime.now())
                    .build();
            user.setRole(effective);
        } else {
            user.setDisplayName(name);
            user.setPhotoUrl(picture);
            if (pinned != null) {
                user.setRole(pinned);
            }
        }
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        List<SimpleGrantedAuthority> authorities = RolePermissions.authorities(effective).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        return new DefaultOAuth2User(authorities, oAuth2User.getAttributes(), "email");
    }
}
