package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.entity.AlertState;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockAlertService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The low stock alert (RPT-06, INV-10): every half hour (app.alerts.low-stock-every; first a minute after
 *               start), the glass whose available m² is below its reorder level. A glass that falls below raises its alert
 *               once (LOW_STOCK:&lt;product id&gt;) and tells the holders of PERM_ALERT_LOW_STOCK; it is cleared when the
 *               glass is back above, and raised again if it falls again.
 * </pre>
 */
@Service
public class StockAlertService {

    static final String PREFIX = "LOW_STOCK:";

    private final StockSummaryService stockSummary;
    private final AlertStateRepository stateRepo;
    private final Notifier notifier;
    private final NumberFormats num;
    private final Clock clock;

    public StockAlertService(StockSummaryService stockSummary, AlertStateRepository stateRepo, Notifier notifier, NumberFormats num, Clock clock) {
        this.stockSummary = stockSummary;
        this.stateRepo = stateRepo;
        this.notifier = notifier;
        this.num = num;
        this.clock = clock;
    }

    /** Raises the alerts of glass newly below its level and clears those back above; how many were raised. */
    @Scheduled(fixedDelayString = "${app.alerts.low-stock-every:PT30M}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkLowStock() {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, AlertState> raised = new HashMap<>();
        stateRepo.findByKeyStartingWithAndClearedAtIsNull(PREFIX).forEach(s -> raised.put(s.getKey(), s));
        int count = 0;
        for (StockSummary.Reorder r : stockSummary.reorder()) {
            String key = PREFIX + r.product().getId();
            if (raised.remove(key) != null) {
                continue;                                        // already told
            }
            AlertState state = stateRepo.findById(key).orElseGet(AlertState::new);
            state.setKey(key);
            state.setRaisedAt(now);
            state.setClearedAt(null);
            stateRepo.save(state);
            notifier.holders("ALERT_LOW_STOCK", null, NotificationKind.LOW_STOCK, "notify.lowStock.title", "notify.lowStock.message",
                    "/stock/summary", r.product().getCode(), num.m2(r.availableM2()), num.m2(r.levelM2()));
            count++;
        }
        raised.values().forEach(s -> s.setClearedAt(now));     // back above their level
        return count;
    }
}
