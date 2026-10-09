package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.AccountType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Account.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : An account of the chart of accounts (ACC-03). The posting rules find theirs by system key
 *               (fixed, seeded by V16), so code and name may change; an account with a key stays active.
 *               The type is fixed once the account has lines. Deactivated, never deleted.
 * </pre>
 */
@Entity
@Table(name = "accounts")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class Account extends BaseEntity {

    @Column(nullable = false, unique = true, length = 10)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 10)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_key", length = 30, updatable = false)
    private AccountKey systemKey;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Used by the posting rules: always active. */
    public boolean isSystem() {
        return systemKey != null;
    }
}
