package com.devanshedutech.security;

import com.devanshedutech.model.Role;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

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

        Role role = adminRegistry.resolve(user.getEmail(), user.getRole());

        // Permissions travel with the session as PERM_* authorities alongside the ROLE_*, so
        // endpoints can authorise against what a role may do rather than against its name.
        List<SimpleGrantedAuthority> authorities = RolePermissions.authorities(role).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPassword())
                .authorities(authorities)
                // A deactivated account keeps its history but can no longer sign in. Spring
                // rejects it during authentication, so this is enforced before any endpoint runs.
                .disabled(!user.isActive())
                .build();
    }
}
