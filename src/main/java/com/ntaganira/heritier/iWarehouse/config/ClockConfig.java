package com.ntaganira.heritier.iWarehouse.config;

import com.ntaganira.heritier.iWarehouse.IWarehouseApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.config
 * - File      : ClockConfig.java
 * - Date      : 2026. 10. 07.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Business clock in Africa/Kigali. Services that depend on the date (numbering periods,
 *               expiry checks) take this Clock so tests can fix the date.
 * </pre>
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of(IWarehouseApplication.TIME_ZONE));
    }
}
