package com.ntaganira.heritier.iWarehouse.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Fills {@link AuditContext} for every request, before Spring Security runs, so login
 * events and data changes share the same request id. Clears it when the request ends.
 *
 * Headers read (sent by the mobile PWA): X-Request-Id, X-Device-Id, X-Client-Time (ISO-8601).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuditContextFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String DEVICE_ID_HEADER = "X-Device-Id";
    public static final String CLIENT_TIME_HEADER = "X-Client-Time";
    private static final ZoneId ZONE = ZoneId.of("Africa/Kigali");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        AuditContext.Data data = AuditContext.current();
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        data.setRequestId(isUuid(requestId) ? requestId : UUID.randomUUID().toString());
        data.setIpAddress(clientIp(request));
        data.setUserAgent(truncate(request.getHeader("User-Agent"), 255));
        data.setDeviceId(truncate(request.getHeader(DEVICE_ID_HEADER), 100));
        data.setClientTime(parseClientTime(request.getHeader(CLIENT_TIME_HEADER)));
        response.setHeader(REQUEST_ID_HEADER, data.getRequestId());
        try {
            chain.doFilter(request, response);
        } finally {
            AuditContext.clear();
        }
    }

    /**
     * Client IP. X-Forwarded-For is NOT read here, because any client can forge it.
     * Behind a reverse proxy, server.forward-headers-strategy=native makes Tomcat
     * resolve getRemoteAddr() from the header only when the request came through a trusted proxy.
     */
    static String clientIp(HttpServletRequest request) {
        return truncate(request.getRemoteAddr(), 45);
    }

    private static boolean isUuid(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static LocalDateTime parseClientTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(ZONE).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
