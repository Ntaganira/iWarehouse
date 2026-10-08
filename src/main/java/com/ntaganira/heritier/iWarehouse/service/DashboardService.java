package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.DailyCounts;
import com.ntaganira.heritier.iWarehouse.dto.DashboardView;
import com.ntaganira.heritier.iWarehouse.dto.Trend;
import com.ntaganira.heritier.iWarehouse.entity.ActivityLog;
import com.ntaganira.heritier.iWarehouse.entity.DataChangeLog;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.repository.ActivityLogRepository;
import com.ntaganira.heritier.iWarehouse.repository.DataChangeLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DashboardService.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Dashboard figures (RPT-01): today's counts against the same hours yesterday,
 *               a 30-day activity chart and the latest actions and record changes
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    /** Days of history sent to the chart. The page shows the last 7 or all of them. */
    public static final int CHART_DAYS = 30;

    private final ActivityLogRepository activityRepo;
    private final DataChangeLogRepository changeRepo;

    public DashboardService(ActivityLogRepository activityRepo, DataChangeLogRepository changeRepo) {
        this.activityRepo = activityRepo;
        this.changeRepo = changeRepo;
    }

    /**
     * @param userId      current user; their own actions are listed when {@code allActivity} is false
     * @param allActivity list everyone's latest actions (caller holds the Activity Logs rights)
     * @param withChanges list the latest record changes (caller holds the Data Changes rights)
     */
    public DashboardView load(Long userId, boolean allActivity, boolean withChanges) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        // Compare today so far with yesterday up to the same time, not with the whole of yesterday.
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime yesterdayNow = now.minusDays(1);

        Trend actions = new Trend(
                activityRepo.countByCreatedAtBetween(todayStart, now),
                activityRepo.countByCreatedAtBetween(yesterdayStart, yesterdayNow));
        Trend changes = new Trend(
                changeRepo.countByServerTimeBetween(todayStart, now),
                changeRepo.countByServerTimeBetween(yesterdayStart, yesterdayNow));
        Trend activeUsers = new Trend(
                activityRepo.countDistinctUsersBetween(todayStart, now),
                activityRepo.countDistinctUsersBetween(yesterdayStart, yesterdayNow));
        Trend failed = new Trend(
                activityRepo.countByStatusAndCreatedAtBetween(ActivityStatus.FAILED, todayStart, now),
                activityRepo.countByStatusAndCreatedAtBetween(ActivityStatus.FAILED, yesterdayStart, yesterdayNow));

        LocalDate first = now.toLocalDate().minusDays(CHART_DAYS - 1);
        DailyCounts actionsPerDay = DailyCounts.of(first, CHART_DAYS,
                DailyCounts.toMap(activityRepo.countPerDaySince(first.atStartOfDay())));
        DailyCounts changesPerDay = DailyCounts.of(first, CHART_DAYS,
                DailyCounts.toMap(changeRepo.countPerDaySince(first.atStartOfDay())));

        List<ActivityLog> recentActivity;
        if (allActivity) {
            recentActivity = activityRepo.findTop6ByOrderByCreatedAtDescIdDesc();
        } else if (userId != null) {
            recentActivity = activityRepo.findTop6ByUserIdOrderByCreatedAtDescIdDesc(userId);
        } else {
            recentActivity = List.of();
        }
        List<DataChangeLog> recentChanges = withChanges
                ? changeRepo.findTop6ByOrderByServerTimeDescIdDesc()
                : List.of();

        return new DashboardView(actions, changes, activeUsers, failed,
                actionsPerDay, changesPerDay, recentActivity, recentChanges);
    }
}
