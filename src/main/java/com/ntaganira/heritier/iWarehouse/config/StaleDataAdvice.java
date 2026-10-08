package com.ntaganira.heritier.iWarehouse.config;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.net.URI;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.config
 * - File      : StaleDataAdvice.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Two people changed the same record at once (NFR-06): @Version refused the second save.
 *               Instead of the error page, send the user back to the page they came from with a clear
 *               message to reload and try again. Only a path of this site is followed back.
 * </pre>
 */
@ControllerAdvice
public class StaleDataAdvice {

    private static final Logger log = LoggerFactory.getLogger(StaleDataAdvice.class);

    private final Messages messages;

    public StaleDataAdvice(Messages messages) {
        this.messages = messages;
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public String stale(RuntimeException e, HttpServletRequest request) {
        log.info("Stale data on {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        RequestContextUtils.getOutputFlashMap(request).put("flashError", messages.get("common.staleData"));
        return "redirect:" + backPath(request.getHeader("Referer"));
    }

    /** The path and query of the referring page of this site, or the dashboard. */
    static String backPath(String referer) {
        if (referer == null || referer.isBlank()) {
            return "/dashboard";
        }
        try {
            URI uri = URI.create(referer);
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                return "/dashboard";
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (IllegalArgumentException ex) {
            return "/dashboard";
        }
    }
}
