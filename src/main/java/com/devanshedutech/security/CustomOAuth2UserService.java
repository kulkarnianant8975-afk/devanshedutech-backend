package com.devanshedutech.security;

import com.devanshedutech.model.User;
import com.devanshedutech.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {
    
    private final UserRepository userRepository;

    public CustomOAuth2UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        
        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");
        String picture = oAuth2User.getAttribute("picture");
        
        User existingUser = userRepository.findByEmailIgnoreCase(email).orElse(null);
        String role = "user";

        // Hardcoded admins (always get admin role)
        if (email != null && (
                email.equalsIgnoreCase("kulkarnianant8975@gmail.com") ||
                email.equalsIgnoreCase("dipaliatdevanshedutech@gmail.com")
        )) {
            role = "admin";
        } else if (existingUser != null && "admin".equalsIgnoreCase(existingUser.getRole())) {
            // Respect existing admin role from DB if already set via SQL
            role = "admin";
        }
        
        if (existingUser == null) {
            User user = User.builder()
                    .id(java.util.UUID.randomUUID().toString())
                    .email(email)
                    .displayName(name)
                    .photoUrl(picture)
                    .role(role)
                    .build();
            userRepository.save(user);
        } else {
            existingUser.setDisplayName(name);
            existingUser.setPhotoUrl(picture);
            existingUser.setRole(role);
            userRepository.save(existingUser);
        }

        return new DefaultOAuth2User(
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role)),
                oAuth2User.getAttributes(),
                "email"
        );
    }
}
