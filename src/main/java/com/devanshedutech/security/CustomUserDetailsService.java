package com.devanshedutech.security;

import com.devanshedutech.model.User;
import com.devanshedutech.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Value("${app.admin.emails:}")
    private String adminEmails;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        if (user.getPassword() == null) {
            throw new UsernameNotFoundException("User uses OAuth2 Login");
        }

        String role = user.getRole();
        
        // Check for hardcoded/env-variable admins
        if (email != null) {
            boolean isAdmin = false;
            if (email.equalsIgnoreCase("kulkarnianant8975@gmail.com") ||
                email.equalsIgnoreCase("dipaliatdevanshedutech@gmail.com")) {
                isAdmin = true;
            } else if (adminEmails != null && !adminEmails.isEmpty()) {
                for (String adminEmail : adminEmails.split(",")) {
                    if (email.equalsIgnoreCase(adminEmail.trim())) {
                        isAdmin = true;
                        break;
                    }
                }
            }
            if (isAdmin) {
                role = "admin";
            }
        }

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPassword())
                .authorities(Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role)))
                .build();
    }
}
