package com.devanshedutech.config;

import com.devanshedutech.security.CustomUserDetailsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@org.springframework.transaction.annotation.EnableTransactionManagement
public class SecurityConfig {

    private final CustomUserDetailsService customUserDetailsService;
    private final com.devanshedutech.security.CustomOAuth2UserService customOAuth2UserService;
    private final com.devanshedutech.security.OAuth2LoginSuccessHandler oauth2LoginSuccessHandler;

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    public SecurityConfig(
            CustomUserDetailsService customUserDetailsService,
            com.devanshedutech.security.CustomOAuth2UserService customOAuth2UserService,
            com.devanshedutech.security.OAuth2LoginSuccessHandler oauth2LoginSuccessHandler) {
        this.customUserDetailsService = customUserDetailsService;
        this.customOAuth2UserService = customOAuth2UserService;
        this.oauth2LoginSuccessHandler = oauth2LoginSuccessHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // Auth is a JSESSIONID cookie, so CSRF protection is required — it was
            // previously disabled on the mistaken grounds of being "stateless".
            // The token is published as a readable XSRF-TOKEN cookie; axios picks it up
            // and echoes it back as X-XSRF-TOKEN automatically (same-origin only).
            // Endpoints that are public and session-less are exempt: a CSRF token would
            // serve no purpose there, and requiring one would break anonymous visitors.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // Spring Security 6 loads the CSRF token lazily: the XSRF-TOKEN cookie is only
                // written if something actually reads the token during a request. Nothing here
                // renders a server-side form, so the cookie was never issued at all — and axios
                // only sends the X-XSRF-TOKEN header when that cookie exists. The result was that
                // every write from the admin UI failed with 403 while login and the public forms
                // kept working, because those are exempt below. Setting the request-attribute name
                // to null opts out of the deferred loading and issues the cookie on every request.
                .csrfTokenRequestHandler(eagerCsrfTokenHandler())
                .ignoringRequestMatchers("/api/chat", "/api/leads", "/api/messages", "/api/auth/login", "/api/auth/register")
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/courses/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/hiring").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/mentors/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/placed-students/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/leads").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/chat").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/messages").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                .successHandler(oauth2LoginSuccessHandler)
            );

        return http.build();
    }

    /**
     * Issues the CSRF token eagerly so the XSRF-TOKEN cookie is present before the client makes
     * its first write. Uses the plain handler rather than the XOR one, because the value the
     * client echoes back must match the cookie it was given.
     */
    private CsrfTokenRequestAttributeHandler eagerCsrfTokenHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(customUserDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Use allowedOriginPatterns for more flexible matching (required for * with credentials)
        configuration.setAllowedOriginPatterns(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Allowing all headers is safer for modern frontend frameworks that send various headers
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setExposedHeaders(Arrays.asList("x-auth-token", "Authorization"));
        configuration.setAllowCredentials(true);
        // Important for preflight (OPTIONS) requests
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
