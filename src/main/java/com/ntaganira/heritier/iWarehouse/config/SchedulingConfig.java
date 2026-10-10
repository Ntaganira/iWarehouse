package com.ntaganira.heritier.iWarehouse.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.config
 * - File      : SchedulingConfig.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Background work (RPT-06): scheduled checks (StockAlertService) and emails sent off the request thread
 *               (AlertMailer), on Spring Boot's default scheduler and task executor.
 * </pre>
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {
}
