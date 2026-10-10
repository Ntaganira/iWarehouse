package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : AlertMailer.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Alerts by email (RPT-06): only when the Settings turn email alerts on and a mail account is configured
 *               (MAIL_USERNAME, MAIL_PASSWORD; MAIL_HOST, MAIL_PORT). Sent off the request thread (@Async), one message per
 *               address with the title, the text and the link to the page; a failure is logged, never shown to the user.
 * </pre>
 */
@Component
public class AlertMailer {

    private static final Logger log = LoggerFactory.getLogger(AlertMailer.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final SettingService settings;
    private final String from;
    private final String appUrl;

    public AlertMailer(ObjectProvider<JavaMailSender> sender, SettingService settings, @Value("${spring.mail.username:}") String from,
                       @Value("${app.url:http://localhost:8080}") String appUrl) {
        this.sender = sender;
        this.settings = settings;
        this.from = from;
        this.appUrl = appUrl;
    }

    /** Email alerts are on and a mail account is set. */
    public boolean enabled() {
        return StringUtils.hasText(from) && sender.getIfAvailable() != null && Boolean.parseBoolean(settings.get(SettingKey.ALERT_EMAIL));
    }

    @Async
    public void send(List<String> to, String subject, String text, String link) {
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null) {
            return;
        }
        String body = text + (link == null ? "" : "\n\n" + appUrl + link);
        for (String address : to) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(address);
                message.setSubject("iWarehouse: " + subject);
                message.setText(body);
                mail.send(message);
            } catch (RuntimeException e) {
                log.warn("Could not email the alert '{}' to {}: {}", subject, address, e.getMessage());
            }
        }
    }
}
