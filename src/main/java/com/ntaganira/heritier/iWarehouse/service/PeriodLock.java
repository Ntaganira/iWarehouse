package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.AccountingPeriodRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PeriodLock.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Refuses what would post on a closed month (ACC-10). JournalService asks it before saving any journal;
 *               documents dated by the user (a manual journal, a supplier invoice) ask it first so the refusal shows
 *               next to the date. It only reads the closed months, so any service may use it (PeriodService, which
 *               closes them, depends on services that post).
 * </pre>
 */
@Component
public class PeriodLock {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final AccountingPeriodRepository repo;

    public PeriodLock(AccountingPeriodRepository repo) {
        this.repo = repo;
    }

    /** The last day of the latest closed month, or null when no month is closed. */
    public LocalDate closedThrough() {
        return repo.closedThrough();
    }

    public boolean isClosed(LocalDate day) {
        return Periods.isClosed(day, closedThrough());
    }

    /** Refused (a toast) when the day is in a closed month. */
    public void requireOpen(LocalDate day) {
        requireOpen(day, null);
    }

    /** Refused next to {@code field} when the day is in a closed month. */
    public void requireOpen(LocalDate day, String field) {
        LocalDate through = closedThrough();
        if (Periods.isClosed(day, through)) {
            String[] args = {through.format(DAY), day.format(DAY)};
            throw field == null ? BusinessException.of("period.closed", (Object[]) args) : BusinessException.onField(field, "period.closed", (Object[]) args);
        }
    }
}
