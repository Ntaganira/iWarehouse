package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.entity.ActivityLog;
import com.ntaganira.heritier.iWarehouse.entity.DataChangeLog;

import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : DashboardView.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Everything the dashboard page shows. Warehouse KPIs join as their modules land (RPT-01).
 * </pre>
 */
public record DashboardView(Trend actions,
                            Trend changes,
                            Trend activeUsers,
                            Trend failed,
                            DailyCounts actionsPerDay,
                            DailyCounts changesPerDay,
                            List<ActivityLog> recentActivity,
                            List<DataChangeLog> recentChanges) {
}
