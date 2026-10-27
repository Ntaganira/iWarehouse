package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Setting;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.SettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CompanyLogoService.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The company's logo (Settings, General), printed on invoices, receipts, quotations and report PDFs. An
 *               upload is redrawn as a PNG (Logos) and kept in MinIO under a new key, stored in the company.logo setting
 *               (audited like any setting); the previous file stays in storage. Pages show it through /settings/logo,
 *               PDFs inside the page (dataUri). The bytes of the current key are kept in memory; storage being down
 *               prints documents without the logo rather than failing them.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class CompanyLogoService {

    private record Cached(String key, byte[] png) {
    }

    private final SettingRepository settingRepo;
    private final FileStorageService storage;
    private volatile Cached cached;

    public CompanyLogoService(SettingRepository settingRepo, FileStorageService storage) {
        this.settingRepo = settingRepo;
        this.storage = storage;
    }

    /** The current logo's storage key, or null when none is set. */
    public String key() {
        return settingRepo.findBySettingKey(SettingKey.COMPANY_LOGO.key()).map(Setting::getSettingValue)
                .filter(StringUtils::hasText).orElse(null);
    }

    /** The address pages show it at; it changes with the logo, so browsers may keep it. Null when none is set. */
    public String url() {
        String key = key();
        return key == null ? null : "/settings/logo?v=" + Integer.toHexString(key.hashCode());
    }

    /** The current logo's PNG; empty when none is set or the storage cannot be read. */
    public Optional<byte[]> png() {
        String key = key();
        if (key == null) {
            return Optional.empty();
        }
        Cached c = cached;
        if (c != null && c.key().equals(key)) {
            return Optional.of(c.png());
        }
        try (InputStream in = storage.open(key)) {
            byte[] png = in.readAllBytes();
            cached = new Cached(key, png);
            return Optional.of(png);
        } catch (BusinessException | IOException e) {
            return Optional.empty();
        }
    }

    /** The logo inside a PDF page (the renderer loads nothing itself), or null. */
    public String dataUri() {
        return png().map(b -> "data:image/png;base64," + Base64.getEncoder().encodeToString(b)).orElse(null);
    }

    /** A new logo: redrawn, stored under a new key, then set. Returns the new key. */
    @Transactional
    public String upload(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw BusinessException.of("settings.logo.empty");
        }
        if (bytes.length > Documents.MAX_BYTES) {
            throw BusinessException.of("settings.logo.tooBig", Documents.MAX_BYTES / (1024 * 1024));
        }
        byte[] png = Logos.png(bytes);
        String key = "company/logo-" + UUID.randomUUID() + ".png";
        storage.put(key, png, "image/png");
        row().setSettingValue(key);
        cached = new Cached(key, png);
        return key;
    }

    /** Documents print without a logo again; the file stays in storage. False when none was set. */
    @Transactional
    public boolean remove() {
        Setting row = row();
        if (!StringUtils.hasText(row.getSettingValue())) {
            return false;
        }
        row.setSettingValue(null);
        return true;
    }

    private Setting row() {
        return settingRepo.findBySettingKey(SettingKey.COMPANY_LOGO.key()).orElseGet(() -> {
            Setting row = new Setting();
            row.setSettingKey(SettingKey.COMPANY_LOGO.key());
            return settingRepo.save(row);
        });
    }
}
