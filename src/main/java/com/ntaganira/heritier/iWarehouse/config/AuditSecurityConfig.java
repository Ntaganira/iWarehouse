package com.ntaganira.heritier.iWarehouse.config;

import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.support.SessionFlashMapManager;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

/** Records logins, failed logins, logouts and access-denied attempts (ported from iVura). */
@Configuration
public class AuditSecurityConfig {

    @Bean
    public AuthenticationSuccessHandler auditSuccessHandler(ActivityLogService activityLogService) {
        SavedRequestAwareAuthenticationSuccessHandler target = new SavedRequestAwareAuthenticationSuccessHandler();
        target.setDefaultTargetUrl("/dashboard");
        return (request, response, authentication) -> {
            activityLogService.record(authentication.getName(), "Authentication", "LOGIN",
                    "Login successful", ActivityStatus.SUCCESS);
            target.onAuthenticationSuccess(request, response, authentication); // back to the page first asked for
        };
    }

    @Bean
    public AuthenticationFailureHandler auditFailureHandler(ActivityLogService activityLogService) {
        return (request, response, exception) -> {
            activityLogService.record(request.getParameter("username"), "Authentication", "LOGIN_FAILED",
                    "Failed login attempt: " + exception.getClass().getSimpleName(), ActivityStatus.FAILED);
            response.sendRedirect("/login?error");
        };
    }

    @Bean
    public LogoutSuccessHandler auditLogoutHandler(ActivityLogService activityLogService) {
        return (request, response, authentication) -> {
            String username = authentication != null ? authentication.getName() : null;
            // session-timeout.js signs an idle user out with ?timeout (NFR-09)
            boolean idle = request.getParameter("timeout") != null;
            activityLogService.record(username, "Authentication", "LOGOUT",
                    idle ? "Signed out automatically after a period without activity" : "User logged out", ActivityStatus.SUCCESS);
            response.sendRedirect(idle ? "/login?timeout=true" : "/login?logout");
        };
    }

    /**
     * A file over the upload limit arrives cut off, so its CSRF token is never read and the request is refused as forged:
     * the user is sent back to the page with "the file is too big" instead of an empty error page. Real refusals keep the
     * 403 page and their ACCESS_DENIED entry.
     */
    @Bean
    public AccessDeniedHandler auditAccessDeniedHandler(ActivityLogService activityLogService, MessageSource messageSource,
                                                        LocaleResolver localeResolver, MultipartProperties multipart) {
        long maxFileBytes = multipart.getMaxFileSize().toBytes();
        FlashMapManager flashMaps = new SessionFlashMapManager();
        return (request, response, accessDeniedException) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String username = auth != null ? auth.getName() : null;
            if (accessDeniedException instanceof CsrfException
                    && OversizedUploads.tooLarge(request.getContentType(), request.getContentLengthLong(), maxFileBytes)) {
                activityLogService.record(username, "Documents", "CREATE_DOCUMENT", "Refused a file of "
                        + OversizedUploads.megabytes(request.getContentLengthLong()) + " sent to " + request.getRequestURI()
                        + ": over the " + OversizedUploads.megabytes(maxFileBytes) + " limit", ActivityStatus.FAILED);
                FlashMap flash = new FlashMap();
                flash.put("flashError", messageSource.getMessage("upload.tooBig", new Object[] {maxFileBytes / (1024 * 1024)},
                        localeResolver.resolveLocale(request)));
                flashMaps.saveOutputFlashMap(flash, request, response);
                response.sendRedirect(OversizedUploads.backTo(request.getHeader("Referer"), request.getServerName()));
                return;
            }
            activityLogService.record(username, "Access Control", "ACCESS_DENIED",
                    "Denied access to " + request.getRequestURI(), ActivityStatus.FAILED);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        };
    }
}
