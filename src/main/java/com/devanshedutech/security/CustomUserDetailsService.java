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
    private final AdminRegistry adminRegistry;

    public CustomUserDetailsService(UserRepository userRepository, AdminRegistry adminRegistry) {
        this.userRepository = userRepository;
        this.adminRegistry = adminRegistry;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        if (user.getPassword() == null) {
            throw new UsernameNotFoundException("User uses OAuth2 Login");
        }

        String role = adminRegistry.resolveRole(user.getEmail(), user.getRole());

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPassword())
                .authorities(Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role)))
                .build();
    }
}
