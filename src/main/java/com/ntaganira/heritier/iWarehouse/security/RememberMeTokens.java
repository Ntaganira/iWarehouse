package com.ntaganira.heritier.iWarehouse.security;

import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.stereotype.Component;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.security
 * - File      : RememberMeTokens.java
 * - Date      : 2026. 10. 28.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Revokes a user's remembered devices when their password, roles or account status change. A remembered
 *               device must not outlive the credentials or access it was issued for.
 * </pre>
 */
@Component
public class RememberMeTokens {

    private final PersistentTokenRepository tokenRepository;

    public RememberMeTokens(PersistentTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    public void revoke(String username) {
        if (username != null) {
            tokenRepository.removeUserTokens(username);
        }
    }
}
