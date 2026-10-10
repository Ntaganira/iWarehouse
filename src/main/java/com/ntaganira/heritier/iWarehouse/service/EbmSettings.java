package com.ntaganira.heritier.iWarehouse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntaganira.heritier.iWarehouse.ebm.HttpVsdcClient;
import com.ntaganira.heritier.iWarehouse.ebm.SimulatedVsdc;
import com.ntaganira.heritier.iWarehouse.ebm.VsdcClient;
import com.ntaganira.heritier.iWarehouse.enums.EbmMode;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : EbmSettings.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The EBM settings (TAX-02) read together, what is still missing before receipts can be signed, and the
 *               VSDC to send them to: the simulator, or the business's VSDC at its URL with short timeouts
 *               (app.ebm.connect-timeout 3 s, app.ebm.read-timeout 15 s), so a sale is never held long by EBM.
 * </pre>
 */
@Component
public class EbmSettings {

    private final SettingService settings;
    private final SimulatedVsdc simulator;
    private final ObjectMapper mapper;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public EbmSettings(SettingService settings, SimulatedVsdc simulator, ObjectMapper mapper,
                       @Value("${app.ebm.connect-timeout:PT3S}") Duration connectTimeout,
                       @Value("${app.ebm.read-timeout:PT15S}") Duration readTimeout) {
        this.settings = settings;
        this.simulator = simulator;
        this.mapper = mapper;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    /** The settings as one value. Item classes and the device serial are checked where they are needed. */
    public record Config(EbmMode mode, String tin, String branchId, String deviceSerial, String vsdcUrl, String glassItemClass,
                         String serviceItemClass, String origin, String receiptUrl, String companyName, String companyAddress) {

        public boolean simulated() {
            return mode == EbmMode.SIMULATOR;
        }

        /** Settings a receipt cannot be sent without, as setting keys. */
        public List<String> missing() {
            List<String> missing = new ArrayList<>();
            if (tin == null) {
                missing.add(SettingKey.COMPANY_TIN.key());
            }
            if (mode == EbmMode.VSDC && vsdcUrl == null) {
                missing.add(SettingKey.EBM_VSDC_URL.key());
            }
            return missing;
        }

        /** Everything the EBM page asks for: the above, the item classes and the device serial (VSDC). */
        public List<String> incomplete() {
            List<String> missing = missing();
            if (glassItemClass == null) {
                missing.add(SettingKey.EBM_GLASS_ITEM_CLASS.key());
            }
            if (serviceItemClass == null) {
                missing.add(SettingKey.EBM_SERVICE_ITEM_CLASS.key());
            }
            if (mode == EbmMode.VSDC && deviceSerial == null) {
                missing.add(SettingKey.EBM_DEVICE_SERIAL.key());
            }
            return missing;
        }
    }

    public Config config() {
        String mode = settings.get(SettingKey.EBM_MODE);
        return new Config(mode == null ? EbmMode.SIMULATOR : EbmMode.valueOf(mode), settings.get(SettingKey.COMPANY_TIN),
                settings.get(SettingKey.EBM_BRANCH_ID), settings.get(SettingKey.EBM_DEVICE_SERIAL), settings.get(SettingKey.EBM_VSDC_URL),
                settings.get(SettingKey.EBM_GLASS_ITEM_CLASS), settings.get(SettingKey.EBM_SERVICE_ITEM_CLASS),
                settings.get(SettingKey.EBM_ORIGIN_COUNTRY), settings.get(SettingKey.EBM_RECEIPT_URL),
                settings.get(SettingKey.COMPANY_NAME), settings.get(SettingKey.COMPANY_ADDRESS));
    }

    public VsdcClient client(Config config) {
        return config.simulated() ? simulator : new HttpVsdcClient(config.vsdcUrl(), mapper, connectTimeout, readTimeout);
    }

    public SimulatedVsdc simulator() {
        return simulator;
    }
}
