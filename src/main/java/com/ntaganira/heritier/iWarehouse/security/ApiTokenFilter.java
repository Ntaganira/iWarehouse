package com.ntaganira.heritier.iWarehouse.security;

import com.ntaganira.heritier.iWarehouse.audit.AuditContextFilter;
import com.ntaganira.heritier.iWarehouse.service.DeviceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.security
 * - File      : ApiTokenFilter.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Authenticates a mobile POS request by its token ("Authorization: Bearer ...") and the phone's id (X-Device-Id)
 *               (NFR-10). Only in the /api/** security chain, never a servlet filter of its own (not a @Component). A
 *               request without a valid token stays anonymous, and the chain answers 401. The phone's id is kept as a
 *               request attribute for the controllers.
 * </pre>
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    /** Request attribute holding the authenticated phone's id (a UUID). */
    public static final String DEVICE_ATTRIBUTE = "iwarehouse.apiDeviceId";

    private final DeviceService deviceService;

    public ApiTokenFilter(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            deviceService.authenticate(token, request.getHeader(AuditContextFilter.DEVICE_ID_HEADER)).ifPresent(a -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(a.user(), null, a.user().getAuthorities()));
                SecurityContextHolder.setContext(context);
                request.setAttribute(DEVICE_ATTRIBUTE, a.deviceId());
            });
        }
        chain.doFilter(request, response);
    }
}
