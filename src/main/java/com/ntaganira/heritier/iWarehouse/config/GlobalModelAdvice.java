package com.ntaganira.heritier.iWarehouse.config;

import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Values every page shell needs (active menu item, profile chip). No database calls. */
@ControllerAdvice
public class GlobalModelAdvice {

    @ModelAttribute("requestURI")
    public String requestURI(HttpServletRequest request) {
        return request.getRequestURI();
    }

    @ModelAttribute("profileFullName")
    public String profileFullName() {
        return AppUserPrincipal.current().map(AppUserPrincipal::getFullName).orElse("");
    }

    @ModelAttribute("profileInitials")
    public String profileInitials() {
        return initials(profileFullName());
    }

    /** Profile photos arrive with the MinIO file service (see TODO.md). */
    @ModelAttribute("profilePhotoUrl")
    public String profilePhotoUrl() {
        return null;
    }

    public static String initials(String fullName) {
        if (!StringUtils.hasText(fullName)) {
            return "";
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, 1).toUpperCase();
        }
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
    }
}
