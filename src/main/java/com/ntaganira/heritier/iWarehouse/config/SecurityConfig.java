package com.ntaganira.heritier.iWarehouse.config;

import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
import org.springframework.core.annotation.Order;
import jakarta.servlet.http.HttpServletResponse;
import com.ntaganira.heritier.iWarehouse.service.DeviceService;
import com.ntaganira.heritier.iWarehouse.security.ApiTokenFilter;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Same model as iVura: form login, one session per user, ROLE_/PAGE_/PERM_ authorities.
 * URL rules only separate public from authenticated; access to each screen and action
 * is enforced with @PreAuthorize on controllers (PAGE_x to open, PERM_x to act).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * The mobile POS app shell (/m/: static files, MPOS-01): open, and without the web session, so an expired back-office
     * session on the same phone never turns the app's files into a redirect to the login page.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain pwaFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/m", "/m/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * The mobile POS API (NFR-10): stateless, no session and no CSRF (no cookie is used), each request authenticated by its
     * phone's token. Signing in and the PWA's texts are open; anything else without a valid token is a 401, a missing right a
     * 403, both as JSON.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http, DeviceService deviceService) throws Exception {
        http
            .securityMatcher("/api/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/messages").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(new ApiTokenFilter(deviceService), UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) -> json(response, 401, "unauthorized"))
                .accessDeniedHandler((request, response, e) -> json(response, 403, "forbidden")));
        return http.build();
    }

    private static void json(HttpServletResponse response, int status, String error) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthenticationSuccessHandler auditSuccessHandler,
                                                   AuthenticationFailureHandler auditFailureHandler,
                                                   LogoutSuccessHandler auditLogoutHandler,
                                                   AccessDeniedHandler auditAccessDeniedHandler) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/images/**", "/vendor/**",
                        "/login", "/actuator/health", "/error").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(auditSuccessHandler)
                .failureHandler(auditFailureHandler)
                .permitAll()
            )
            .logout(logout -> logout
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler(auditLogoutHandler)
                .permitAll()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(fixation -> fixation.changeSessionId())
                .invalidSessionUrl("/login?expired=true")
                .maximumSessions(1)
                .maxSessionsPreventsLogin(false)
                .expiredUrl("/login?expired=true")
                .sessionRegistry(sessionRegistry())
            )
            .exceptionHandling(ex -> ex.accessDeniedHandler(auditAccessDeniedHandler));
        return http.build();
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Lets the session registry hear about expired sessions (needed by maximumSessions(1)). */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepo) {
        return username -> userRepo.findByUsername(username)
            .map(user -> new AppUserPrincipal(
                    user.getId(),
                    user.getFullName(),
                    user.getUsername(),
                    user.getPassword(),
                    user.isEnabled(),
                    user.getLockedUntil() == null || user.getLockedUntil().isBefore(LocalDateTime.now()),
                    buildAuthorities(user)))
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    /**
     * ROLE_X from the role name, PAGE_CODE for each page and PERM_CODE for each permission the role holds.
     */
    private Set<SimpleGrantedAuthority> buildAuthorities(User user) {
        Set<SimpleGrantedAuthority> authorities = new HashSet<>();
        if (user.getRoles() == null) {
            return authorities;
        }
        user.getRoles().stream().filter(r -> r.isEnabled()).forEach(role -> {
            authorities.add(new SimpleGrantedAuthority(role.getName()));
            role.getPermissions().stream().filter(p -> p.isEnabled())
                    .forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p.getCode())));
            role.getPages().stream().filter(p -> p.isEnabled())
                    .forEach(p -> authorities.add(new SimpleGrantedAuthority("PAGE_" + p.getCode())));
        });
        return authorities;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
