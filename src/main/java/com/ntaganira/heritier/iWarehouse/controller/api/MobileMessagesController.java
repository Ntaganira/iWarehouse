package com.ntaganira.heritier.iWarehouse.controller.api;

import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller.api
 * - File      : MobileMessagesController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The mobile POS's texts in a language (en, fr, rw): every mobile.* key of the message bundles, so the PWA's
 *               words live in messages*.properties like every other screen's. Open (the sign-in screen needs them) and kept
 *               by the service worker for offline use.
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/messages")
public class MobileMessagesController {

    private static final List<String> LANGUAGES = List.of("en", "fr", "rw");
    private static final List<String> PREFIXES = List.of("mobile.", "sync.reason.");

    private final MessageSource messageSource;
    private final List<String> keys;

    public MobileMessagesController(MessageSource messageSource) {
        this.messageSource = messageSource;
        this.keys = keys();
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> messages(@RequestParam(defaultValue = "en") String lang) {
        Locale locale = Locale.forLanguageTag(LANGUAGES.contains(lang) ? lang : "en");
        Map<String, String> texts = new TreeMap<>();
        for (String key : keys) {
            texts.put(key, messageSource.getMessage(key, null, key, locale));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES)).body(texts);
    }

    /** The keys of the default bundle the PWA uses. */
    private static List<String> keys() {
        Properties bundle = new Properties();
        try (InputStream in = new ClassPathResource("messages.properties").getInputStream()) {
            bundle.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bundle.stringPropertyNames().stream().filter(k -> PREFIXES.stream().anyMatch(k::startsWith)).sorted().toList();
    }
}
