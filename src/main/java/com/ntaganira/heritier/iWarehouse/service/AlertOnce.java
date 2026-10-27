package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.AlertState;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;

import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : AlertOnce.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A watched condition told once (RPT-06): raised in alert_states under its key unless it is raised
 *               already, cleared when it ends so it can be told again if it comes back.
 * </pre>
 */
final class AlertOnce {

    private AlertOnce() {
    }

    /** Raises the alert; false when it is raised already (nobody is told twice). */
    static boolean raise(AlertStateRepository repo, String key, LocalDateTime now) {
        AlertState state = repo.findById(key).orElseGet(AlertState::new);
        if (state.getKey() != null && state.getClearedAt() == null) {
            return false;
        }
        state.setKey(key);
        state.setRaisedAt(now);
        state.setClearedAt(null);
        repo.save(state);
        return true;
    }

    static void clear(AlertStateRepository repo, String key, LocalDateTime now) {
        repo.findById(key).filter(s -> s.getClearedAt() == null).ifPresent(s -> s.setClearedAt(now));
    }
}
