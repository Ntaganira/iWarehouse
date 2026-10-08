package com.ntaganira.heritier.iWarehouse.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : AfterCommit.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Runs work only once the current transaction has committed (activity entries that
 *               describe a change, ending sessions), so nothing is logged or done for a rolled-back change.
 * </pre>
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
