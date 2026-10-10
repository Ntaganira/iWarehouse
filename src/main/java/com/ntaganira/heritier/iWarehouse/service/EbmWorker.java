package com.ntaganira.heritier.iWarehouse.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : EbmWorker.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Sends EBM receipts (TAX-02, TAX-03, AT-09). A receipt is tried once right after the transaction that
 *               queued it commits (the sale is already complete; the VSDC's short timeouts bound the wait), then every
 *               30 s (app.ebm.retry-every) the due receipts are tried, oldest number first, at most 50 a run; a run stops
 *               at the first VSDC that cannot be reached, so an outage costs one timeout, not fifty. Each run then checks
 *               the backlog alert (app.ebm.backlog-alert-after, 1 h).
 * </pre>
 */
@Component
public class EbmWorker {

    private static final Logger log = LoggerFactory.getLogger(EbmWorker.class);
    private static final int BATCH = 50;

    private final EbmSigner signer;
    private final EbmService ebmService;
    private final Duration backlogAfter;

    public EbmWorker(EbmSigner signer, EbmService ebmService, @Value("${app.ebm.backlog-alert-after:PT1H}") Duration backlogAfter) {
        this.signer = signer;
        this.ebmService = ebmService;
        this.backlogAfter = backlogAfter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQueued(EbmService.Queued event) {
        try {
            signer.attempt(event.receiptId());
        } catch (RuntimeException e) {
            log.warn("EBM receipt {}: the attempt after issuing failed; the queue retries it", event.receiptId(), e);
        }
    }

    @Scheduled(fixedDelayString = "${app.ebm.retry-every:PT30S}", initialDelayString = "${app.ebm.first-run:PT30S}")
    public void run() {
        for (UUID id : ebmService.due(BATCH)) {
            try {
                if (signer.attempt(id) == EbmSigner.Attempt.UNREACHABLE) {
                    break;
                }
            } catch (RuntimeException e) {
                log.warn("EBM receipt {}: attempt failed", id, e);
            }
        }
        try {
            ebmService.checkBacklog(backlogAfter);
        } catch (RuntimeException e) {
            log.warn("EBM backlog check failed", e);
        }
    }
}
