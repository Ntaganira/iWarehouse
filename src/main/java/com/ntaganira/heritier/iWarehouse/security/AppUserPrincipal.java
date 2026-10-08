package com.ntaganira.heritier.iWarehouse.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.Optional;

/**
 * Logged-in user. Carries the database id and full name so audit code and views never
 * need to query the users table (which must not happen during a Hibernate flush).
 */
public class AppUserPrincipal extends User {

    private final Long id;
    private final String fullName;

    public AppUserPrincipal(Long id, String fullName, String username, String password, boolean enabled,
                            boolean accountNonLocked, Collection<? extends GrantedAuthority> authorities) {
        super(username, password, enabled, true, true, accountNonLocked, authorities);
        this.id = id;
        this.fullName = fullName;
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    /** The current principal, if the request is authenticated with an AppUserPrincipal. */
    public static Optional<AppUserPrincipal> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AppUserPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }

    /** True when the current request holds the authority, e.g. "PERM_ASSIGN_ROLE". */
    public static boolean currentHas(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    /** Username of the current user, or null for anonymous / system work. */
    public static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return auth.getName();
    }
}
