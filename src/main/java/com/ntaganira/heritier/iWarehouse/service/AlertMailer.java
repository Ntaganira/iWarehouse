package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : AlertMailer.java
 * - Date      : 2026. 10. 10.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Alerts by email (RPT-06): only when the Settings turn email alerts on and a mail account is configured
 *               (MAIL_USERNAME, MAIL_PASSWORD; MAIL_HOST, MAIL_PORT). Sent off the request thread (@Async), one message per
 *               address with the official HTML template (MailTemplates) and a plain-text fallback; a failure is logged,
 *               never shown to the user.
 * </pre>
 */
@Component
public class AlertMailer {

    private static final Logger log = LoggerFactory.getLogger(AlertMailer.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final SettingService settings;
    private final MailTemplates templates;
    private final CompanyLogoService logos;
    private final String from;
    private final String appUrl;

    public AlertMailer(ObjectProvider<JavaMailSender> sender, SettingService settings, MailTemplates templates,
                       CompanyLogoService logos, @Value("${spring.mail.username:}") String from,
                       @Value("${app.url:http://localhost:8080}") String appUrl) {
        this.sender = sender;
        this.settings = settings;
        this.templates = templates;
        this.logos = logos;
        this.from = from;
        this.appUrl = appUrl;
    }

    /** Email alerts are on and a mail account is set. */
    public boolean enabled() {
        return StringUtils.hasText(from) && sender.getIfAvailable() != null && Boolean.parseBoolean(settings.get(SettingKey.ALERT_EMAIL));
    }

    @Async
    public void send(List<String> to, String subject, String text, String link, NotificationKind kind) {
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null) {
            return;
        }
        String absoluteLink = absolute(link);
        String plain = text + (absoluteLink == null ? "" : "\n\n" + absoluteLink);
        String html = null;
        try {
            html = templates.render(subject, text, link, kind);
        } catch (RuntimeException e) {
            log.warn("Could not render the alert email '{}': {}", subject, e.getMessage());
        }
        byte[] logo = html == null ? null : logos.png().orElse(null);
        for (String address : to) {
            try {
                MimeMessage message = mail.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, html != null, StandardCharsets.UTF_8.name());
                helper.setFrom(from);
                helper.setTo(address);
                helper.setSubject("iWarehouse: " + subject);
                if (html == null) {
                    helper.setText(plain);
                } else {
                    helper.setText(plain, html);
                    if (logo != null) {
                        helper.addInline("logo", new ByteArrayResource(logo), "image/png");
                    }
                }
                mail.send(message);
            } catch (Exception e) {
                log.warn("Could not email the alert '{}' to {}: {}", subject, address, e.getMessage());
            }
        }
    }

    private String absolute(String link) {
        if (!StringUtils.hasText(link)) {
            return null;
        }
        if (link.startsWith("http://") || link.startsWith("https://")) {
            return link;
        }
        String base = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
        return base + (link.startsWith("/") ? link : "/" + link);
    }
}
