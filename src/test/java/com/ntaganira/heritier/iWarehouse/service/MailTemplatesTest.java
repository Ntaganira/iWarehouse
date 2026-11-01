package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The official alert email (RPT-06): the company's name, logo and details from Settings, the alert's title and text,
 * and an absolute button to its page.
 */
class MailTemplatesTest {

    private final SettingService settings = mock(SettingService.class);
    private final CompanyLogoService logos = mock(CompanyLogoService.class);
    private MailTemplates templates;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        engine.setTemplateResolver(resolver);
        when(settings.get(SettingKey.COMPANY_NAME)).thenReturn("High Bytes Glass");
        when(settings.get(SettingKey.COMPANY_ADDRESS)).thenReturn("Kigali, Rwanda");
        when(settings.get(SettingKey.COMPANY_PHONE)).thenReturn("+250 788 000 000");
        when(settings.get(SettingKey.COMPANY_EMAIL)).thenReturn("hello@highbytes.rw");
        when(settings.get(SettingKey.COMPANY_TIN)).thenReturn("100200300");
        when(logos.png()).thenReturn(Optional.of(new byte[]{1, 2, 3}));
        templates = new MailTemplates(engine, new Messages(source), settings, logos, "https://erp.example.rw");
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void rendersTheCompanyTheAlertAndAnAbsoluteButton() {
        String html = templates.render("Sale approval needed", "A 15% discount on CLR-6 6 mm waits for your decision.",
                "/pos/approvals/1", NotificationKind.APPROVAL);

        assertThat(html).contains("High Bytes Glass", "Kigali, Rwanda", "+250 788 000 000", "hello@highbytes.rw", "100200300");
        assertThat(html).contains("Sale approval needed", "A 15% discount on CLR-6 6 mm waits for your decision.");
        assertThat(html).contains("Approval", "Open iWarehouse");
        assertThat(html).contains("https://erp.example.rw/pos/approvals/1");
        assertThat(html).contains("cid:logo");
        assertThat(html).doesNotContain("??");
    }

    @Test
    void withoutALinkThereIsNoButton() {
        String html = templates.render("Stock is low", "CLR-4 is below its reorder level.", null, NotificationKind.LOW_STOCK);

        assertThat(html).contains("Stock is low", "CLR-4 is below its reorder level.");
        assertThat(html).doesNotContain("Open iWarehouse");
    }
}
