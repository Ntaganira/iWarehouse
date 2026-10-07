package com.ntaganira.heritier.iWarehouse.config;

import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
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
            activityLogService.record(username, "Authentication", "LOGOUT",
                    "User logged out", ActivityStatus.SUCCESS);
            response.sendRedirect("/login?logout");
        };
    }

    @Bean
    public AccessDeniedHandler auditAccessDeniedHandler(ActivityLogService activityLogService) {
        return (request, response, accessDeniedException) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String username = auth != null ? auth.getName() : null;
            activityLogService.record(username, "Access Control", "ACCESS_DENIED",
                    "Denied access to " + request.getRequestURI(), ActivityStatus.FAILED);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        };
    }
}
