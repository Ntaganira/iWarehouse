-- =====================================================================
-- V59: persistent remember-me tokens (login "Remember me").
-- Spring Security's PersistentTokenBasedRememberMeServices stores one
-- opaque series/token pair per remembered device. The cookie holds no
-- username or password; logging out removes the user's tokens, and so
-- does a password change, role change or disabled account.
-- =====================================================================

CREATE TABLE persistent_logins (
    username  VARCHAR(64) NOT NULL,
    series    VARCHAR(64) PRIMARY KEY,
    token     VARCHAR(64) NOT NULL,
    last_used TIMESTAMP   NOT NULL
);

CREATE INDEX idx_persistent_logins_username ON persistent_logins (username);
