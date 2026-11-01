package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : MailTemplates.java
 * - Date      : 2026. 11. 01.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Renders the official iWarehouse email notification (templates/mail/alert.html) with the company's
 *               name, logo and contact details from Settings, the alert's title and text, and a button to its page.
 * </pre>
 */
@Component
public class MailTemplates {

    private final TemplateEngine engine;
    private final Messages messages;
    private final SettingService settings;
    private final CompanyLogoService logos;
    private final String appUrl;

    public MailTemplates(TemplateEngine engine, Messages messages, SettingService settings, CompanyLogoService logos,
                         @Value("${app.url:http://localhost:8080}") String appUrl) {
        this.engine = engine;
        this.messages = messages;
        this.settings = settings;
        this.logos = logos;
        this.appUrl = appUrl;
    }

    /** The alert as an HTML email, ready for a mail client. */
    public String render(String title, String text, String link, NotificationKind kind) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("title", title);
        context.setVariable("message", text == null ? "" : text);
        context.setVariable("link", absolute(link));
        context.setVariable("badge", messages.get(badgeKey(kind)));
        context.setVariable("kicker", messages.get("mail.alert.kicker"));
        context.setVariable("product", messages.get("mail.alert.product"));
        context.setVariable("openLabel", messages.get("mail.alert.open"));
        context.setVariable("linkLabel", messages.get("mail.alert.link"));
        context.setVariable("automated", messages.get("mail.alert.automated"));
        context.setVariable("tinLabel", messages.get("mail.alert.tin"));
        context.setVariable("confidential", messages.get("mail.alert.confidential"));
        context.setVariable("companyName", value(SettingKey.COMPANY_NAME, "iWarehouse"));
        context.setVariable("companyAddress", settings.get(SettingKey.COMPANY_ADDRESS));
        context.setVariable("companyTin", settings.get(SettingKey.COMPANY_TIN));
        String phone = settings.get(SettingKey.COMPANY_PHONE);
        String email = settings.get(SettingKey.COMPANY_EMAIL);
        context.setVariable("companyContact", contact(phone, email));
        context.setVariable("logoUrl", logos.png().isPresent() ? "cid:logo" : null);
        return engine.process("mail/alert", context);
    }

    private String badgeKey(NotificationKind kind) {
        if (kind == NotificationKind.APPROVAL) {
            return "mail.alert.badge.approval";
        }
        if (kind == NotificationKind.DECISION) {
            return "mail.alert.badge.decision";
        }
        return "mail.alert.badge.notification";
    }

    private String value(SettingKey key, String fallback) {
        String value = settings.get(key);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String contact(String phone, String email) {
        if (StringUtils.hasText(phone) && StringUtils.hasText(email)) {
            return phone + " \u00b7 " + email;
        }
        return StringUtils.hasText(phone) ? phone : (StringUtils.hasText(email) ? email : null);
    }

    private String absolute(String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        String base = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
        return base + (path.startsWith("/") ? path : "/" + path);
    }
}
