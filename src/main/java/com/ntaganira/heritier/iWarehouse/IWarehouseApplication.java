package com.ntaganira.heritier.iWarehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse
 * - File      : IWarehouseApplication.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Application entry point
 * </pre>
 */
@SpringBootApplication
public class IWarehouseApplication {

    /** Business time zone (Rwanda, CAT). All LocalDateTime values in the app and database use it. */
    public static final String TIME_ZONE = "Africa/Kigali";

    public static void main(String[] args) {
        // Set before anything starts: the PostgreSQL driver sends this zone as the session TimeZone,
        // so CURRENT_TIMESTAMP defaults and Java LocalDateTime.now() agree.
        TimeZone.setDefault(TimeZone.getTimeZone(TIME_ZONE));
        SpringApplication.run(IWarehouseApplication.class, args);
    }
}
