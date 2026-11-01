package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : WhatsAppNotifier.java
 * - Date      : 2026. 10. 31.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Sends a sale confirmation to the customer's WhatsApp number (TASK.md). It runs
 *               off the request thread once the sale is committed, so a slow or failing gateway
 *               never changes the sale. The number is the customer's WhatsApp number, else the
 *               phone on their account; a customer without either, or the feature turned off,
 *               sends nothing.
 * </pre>
 */
@Service
public class WhatsAppNotifier {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppNotifier.class);

    private final SalesInvoiceRepository invoiceRepo;
    private final SettingService settings;
    private final WhatsAppGateway gateway;
    private final MessageSource messages;

    public WhatsAppNotifier(SalesInvoiceRepository invoiceRepo, SettingService settings, WhatsAppGateway gateway, MessageSource messages) {
        this.invoiceRepo = invoiceRepo;
        this.settings = settings;
        this.gateway = gateway;
        this.messages = messages;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSaleIssued(SaleIssued event) {
        saleIssued(event.invoiceId());
    }

    public void saleIssued(UUID invoiceId) {
        if (!settings.getBoolean(SettingKey.WHATSAPP_ENABLED) || !gateway.configured()) {
            return;
        }
        SalesInvoice invoice = invoiceRepo.findDetailedById(invoiceId).orElse(null);
        if (invoice == null || !"POSTED".equals(invoice.getStatus().name())) {
            return;
        }
        Customer customer = invoice.getCustomer();
        String raw = StringUtils.hasText(customer.getWhatsappNumber()) ? customer.getWhatsappNumber() : customer.getPhone();
        String number = WhatsAppNumbers.international(raw);
        if (number == null) {
            return;
        }
        String text = messages.getMessage("whatsapp.sale.message",
                new Object[]{settings.get(SettingKey.COMPANY_NAME), invoice.getNumber(),
                        invoice.getTotalAmount().stripTrailingZeros().toPlainString(),
                        String.valueOf(invoice.getDueDate())},
                Locale.getDefault());
        try {
            gateway.send(number, text);
        } catch (RuntimeException e) {
            log.warn("Could not send the WhatsApp confirmation of {}: {}", invoice.getNumber(), e.getMessage());
        }
    }
}
