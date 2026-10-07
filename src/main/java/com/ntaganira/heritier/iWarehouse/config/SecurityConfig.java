package com.ntaganira.heritier.iWarehouse.config;

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

    @Bean
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
