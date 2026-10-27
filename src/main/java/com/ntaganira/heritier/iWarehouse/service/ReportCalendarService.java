package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ReportCalendarService.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The reporting calendar's reminders (BP 5.3, M.11), told once each to ALERT_REPORT_CALENDAR holders: the
 *               weekly receivables and stock on Mondays (with the receivables' total, the share more than 90 days
 *               overdue and the days sales outstanding), the month's accounts not closed by the Settings' working day,
 *               the quarterly Board pack once a quarter has ended. The daily cash report's deadline is the daily close's
 *               (DailyCloseService.checkLate).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ReportCalendarService {

    static final String ALERT_PREFIX = "CALENDAR:";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final CustomerAccountService accountService;
    private final CreditControlService creditControl;
    private final PeriodLock periodLock;
    private final SettingService settingService;
    private final AlertStateRepository alertRepo;
    private final Notifier notifier;
    private final Clock clock;

    public ReportCalendarService(CustomerAccountService accountService, CreditControlService creditControl, PeriodLock periodLock,
                                 SettingService settingService, AlertStateRepository alertRepo, Notifier notifier, Clock clock) {
        this.accountService = accountService;
        this.creditControl = creditControl;
        this.periodLock = periodLock;
        this.settingService = settingService;
        this.alertRepo = alertRepo;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Tells what the calendar asks now, once each; how many reminders went out. */
    @Scheduled(fixedDelayString = "${app.alerts.calendar-every:PT1H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int check() {
        LocalDateTime now = LocalDateTime.now(clock);
        int count = 0;
        Optional<String> weekly = ReportCalendar.weekly(now);
        if (weekly.isPresent() && AlertOnce.raise(alertRepo, ALERT_PREFIX + weekly.get(), now)) {
            CreditControlService.Kpis kpis = creditControl.kpis(accountService.receivables());
            notifier.holders("ALERT_REPORT_CALENDAR", null, NotificationKind.CLOSE, "notify.calendar.weekly", "notify.calendar.weeklyText",
                    "/accounting/receivables", CashDepositService.rwf(kpis.receivables()), kpis.over90Percent().toPlainString(),
                    kpis.dso() == null ? "-" : kpis.dso().toPlainString());
            count++;
        }
        LocalDate today = now.toLocalDate();
        Optional<YearMonth> late = ReportCalendar.accountsLate(today, settingService.getInt(SettingKey.CALENDAR_ACCOUNTS_WORKING_DAY));
        if (late.isPresent() && !periodLock.isClosed(late.get().atEndOfMonth())
                && AlertOnce.raise(alertRepo, ALERT_PREFIX + "MONTH:" + late.get(), now)) {
            notifier.holders("ALERT_REPORT_CALENDAR", null, NotificationKind.CLOSE, "notify.calendar.accountsLate",
                    "notify.calendar.accountsLateText", "/accounting/periods", late.get().format(MONTH),
                    String.valueOf(settingService.getInt(SettingKey.CALENDAR_ACCOUNTS_WORKING_DAY)));
            count++;
        }
        Optional<String> quarter = ReportCalendar.quarterEnded(today);
        if (quarter.isPresent() && AlertOnce.raise(alertRepo, ALERT_PREFIX + "QUARTER:" + quarter.get(), now)) {
            notifier.holders("ALERT_REPORT_CALENDAR", null, NotificationKind.CLOSE, "notify.calendar.boardPack", "notify.calendar.boardPackText",
                    "/accounting/income-statement", quarter.get());
            count++;
        }
        return count;
    }
}
