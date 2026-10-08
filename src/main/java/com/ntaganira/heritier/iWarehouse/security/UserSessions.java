package com.ntaganira.heritier.iWarehouse.security;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.security
 * - File      : UserSessions.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Ends a user's open sessions, so a disabled account, a reset password or new roles
 *               take effect at once instead of at the next sign-in. The user lands on /login?expired.
 * </pre>
 */
@Component
public class UserSessions {

    private final SessionRegistry sessionRegistry;

    public UserSessions(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    public void expire(Long userId) {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof AppUserPrincipal p && p.getId().equals(userId)) {
                sessionRegistry.getAllSessions(p, false).forEach(SessionInformation::expireNow);
            }
        }
    }
}
