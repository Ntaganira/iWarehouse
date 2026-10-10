package com.ntaganira.heritier.iWarehouse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ntaganira.heritier.iWarehouse.ebm.*;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.EbmReceiptStatus;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : EbmSigner.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : One attempt to sign a queued EBM receipt (TAX-02, TAX-03, AT-09), in its own transaction, the receipt
 *               locked (a receipt being signed elsewhere is skipped). The request is built from the issued document,
 *               which never changes, so every attempt sends the same receipt under the same invoice number. A refund
 *               waits for its sale's signature. Glass and processing not registered with this VSDC (or registered
 *               differently) are registered first. Then:
 *               - signed (000): the VSDC's numbers, internal data and signature are kept; the receipt never changes;
 *               - the VSDC cannot be reached, a setting is missing, or the VSDC answers a retried code: it stays queued,
 *                 tried again after 30 s, 1 min, 2 min ... at most 30 min (VsdcResults);
 *               - refused (its data, or RRA already holds the number): failed, the EBM alert's holders are told, and a
 *                 person retries it from the EBM page once fixed.
 * </pre>
 */
@Service
public class EbmSigner {

    private static final Logger log = LoggerFactory.getLogger(EbmSigner.class);

    /** What an attempt did. */
    public enum Attempt {
        SKIPPED, SIGNED, WAITING, UNREACHABLE, FAILED
    }

    /** Pseudo result codes for what never reached the VSDC. */
    static final String CONFIG = "CFG";
    static final String NETWORK = "NET";
    static final String WAITING_SALE = "WAIT";
    static final String ERROR = "ERR";

    private final EbmReceiptRepository receipts;
    private final EbmItemRepository items;
    private final EbmDeviceRepository devices;
    private final SalesInvoiceRepository invoices;
    private final SalesPaymentRepository payments;
    private final CreditNoteRepository creditNotes;
    private final CreditNoteLineRepository creditLines;
    private final TaxCategoryRepository taxCategories;
    private final EbmSettings ebmSettings;
    private final Notifier notifier;
    private final Clock clock;

    public EbmSigner(EbmReceiptRepository receipts, EbmItemRepository items, EbmDeviceRepository devices, SalesInvoiceRepository invoices,
                     SalesPaymentRepository payments, CreditNoteRepository creditNotes, CreditNoteLineRepository creditLines,
                     TaxCategoryRepository taxCategories, EbmSettings ebmSettings, Notifier notifier, Clock clock) {
        this.receipts = receipts;
        this.items = items;
        this.devices = devices;
        this.invoices = invoices;
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.creditLines = creditLines;
        this.taxCategories = taxCategories;
        this.ebmSettings = ebmSettings;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** A setting the document needs is empty. */
    static class MissingSetting extends RuntimeException {
        MissingSetting(String key) {
            super("Missing setting: " + key);
        }
    }

    /** The VSDC refused to register an item. */
    static class ItemRefused extends RuntimeException {
        ItemRefused(String message) {
            super(message);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Attempt attempt(UUID id) {
        EbmReceipt receipt = receipts.lockForSigning(id).orElse(null);
        if (receipt == null || !receipt.isQueued()) {
            return Attempt.SKIPPED;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        receipt.setAttempts(receipt.getAttempts() + 1);
        receipt.setLastAttemptAt(now);
        if (receipt.isRefund()) {
            Optional<EbmReceipt> sale = receipts.findSale(receipt.getInvoiceId());
            if (sale.isEmpty() || !sale.get().isSigned()) {
                return postpone(receipt, now, WAITING_SALE, "Its invoice is not signed yet");
            }
        }
        EbmSettings.Config config = ebmSettings.config();
        List<String> missing = config.missing();
        if (!missing.isEmpty()) {
            return postpone(receipt, now, CONFIG, "Missing settings: " + String.join(", ", missing));
        }
        VsdcClient client = ebmSettings.client(config);
        try {
            EbmRequests.Document document = receipt.isRefund() ? refund(receipt, config, client) : sale(receipt, config, client);
            Vsdc.Reply<Vsdc.Signature> reply = client.saveSale(EbmRequests.sale(config.tin(), config.branchId(), document));
            receipt.setRequestJson(reply.requestJson());
            receipt.setResponseJson(reply.responseJson());
            receipt.setResultCode(EbmCodes.cut(reply.code(), 5));
            return switch (VsdcResults.of(reply.code())) {
                case SIGNED -> sign(receipt, reply.data(), client.simulated(), now);
                case RETRY -> postpone(receipt, now, null, reply.message());
                case DUPLICATE -> fail(receipt, now, "RRA already holds invoice number " + receipt.getInvcNo()
                        + " (an earlier attempt was probably signed): " + reply.message());
                case REJECTED -> fail(receipt, now, reply.message());
            };
        } catch (VsdcUnavailableException e) {
            receipt.setRequestJson(e.getRequestJson());
            postpone(receipt, now, NETWORK, e.getMessage());
            return Attempt.UNREACHABLE;
        } catch (MissingSetting | IllegalArgumentException e) {
            return postpone(receipt, now, CONFIG, e.getMessage());
        } catch (ItemRefused e) {
            receipt.setResultCode(ERROR);
            return fail(receipt, now, e.getMessage());
        } catch (RuntimeException e) {
            log.error("EBM receipt {} of {}: attempt failed", receipt.getInvcNo(), receipt.getDocumentNumber(), e);
            return postpone(receipt, now, ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ outcomes

    private Attempt sign(EbmReceipt receipt, Vsdc.Signature s, boolean simulated, LocalDateTime now) {
        if (s == null || !s.isComplete()) {
            return fail(receipt, now, "The VSDC answered 000 without a complete signature");
        }
        // Status and the fields its check needs, together
        receipt.setRcptNo(s.rcptNo());
        receipt.setTotRcptNo(s.totRcptNo());
        receipt.setIntrlData(EbmCodes.cut(s.intrlData(), 40));
        receipt.setRcptSign(EbmCodes.cut(s.rcptSign(), 40));
        receipt.setSdcId(EbmCodes.cut(s.sdcId(), 20));
        receipt.setMrcNo(EbmCodes.cut(s.mrcNo(), 20));
        receipt.setVsdcDate(EbmCodes.parseDateTime(s.vsdcRcptPbctDate()));
        receipt.setSimulated(simulated);
        receipt.setLastError(null);
        receipt.setNextAttemptAt(null);
        receipt.setSignedAt(now);
        receipt.setStatus(EbmReceiptStatus.SIGNED);
        return Attempt.SIGNED;
    }

    private Attempt postpone(EbmReceipt receipt, LocalDateTime now, String code, String error) {
        if (code != null) {
            receipt.setResultCode(code);
        }
        receipt.setLastError(EbmCodes.cut(error, 500));
        receipt.setNextAttemptAt(now.plus(VsdcResults.retryDelay(receipt.getAttempts())));
        return Attempt.WAITING;
    }

    private Attempt fail(EbmReceipt receipt, LocalDateTime now, String error) {
        receipt.setLastError(EbmCodes.cut(error, 500));
        receipt.setNextAttemptAt(null);
        receipt.setStatus(EbmReceiptStatus.FAILED);
        notifier.holders("ALERT_EBM", null, NotificationKind.EBM, "notify.ebm.failed.title", "notify.ebm.failed.message",
                "/ebm/" + receipt.getId(), receipt.getDocumentNumber(), EbmCodes.cut(error, 200));
        return Attempt.FAILED;
    }

    // ------------------------------------------------------------------ documents

    private EbmRequests.Document sale(EbmReceipt receipt, EbmSettings.Config config, VsdcClient client) {
        SalesInvoice invoice = invoices.findDetailedById(receipt.getInvoiceId())
                .orElseThrow(() -> new IllegalStateException("Invoice of " + receipt.getDocumentNumber() + " not found"));
        List<SalesPayment> paid = payments.findByInvoiceIdOrderByLineNo(invoice.getId()).stream()
                .filter(p -> !p.isBalancePayment()).toList();
        Set<PaymentMethod> methods = EnumSet.noneOf(PaymentMethod.class);
        paid.forEach(p -> methods.add(p.getMethod()));
        BigDecimal paidAtIssue = paid.stream().map(SalesPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean balanceDue = paidAtIssue.compareTo(invoice.getTotalAmount()) < 0;
        List<EbmRequests.Line> lines = new ArrayList<>();
        for (SalesInvoiceLine line : sorted(invoice.getLines())) {
            lines.add(line(line, line.getAmount(), line.isServiceLine() ? line.getServiceQuantity()
                    : line.getChargeableAreaM2().multiply(BigDecimal.valueOf(line.getQuantity())), config, client, invoice.getPostedBy()));
        }
        return new EbmRequests.Document(receipt.getInvcNo(), 0, EbmCodes.SALE, invoice.getBuyerTin(), invoice.getBillTo(),
                invoice.getCustomer().getPhone(), receipt.getPurchaseCode(), EbmCodes.paymentType(methods, balanceDue),
                invoice.getPostedAt(), invoice.getInvoiceDate(), null, reportNo(config, client, invoice.getPostedAt()),
                invoice.getPostedBy(), config.companyName(), config.companyAddress(), rates(), lines);
    }

    private EbmRequests.Document refund(EbmReceipt receipt, EbmSettings.Config config, VsdcClient client) {
        CreditNote note = creditNotes.findDetailedById(receipt.getCreditNoteId())
                .orElseThrow(() -> new IllegalStateException("Credit note " + receipt.getDocumentNumber() + " not found"));
        SalesInvoice invoice = invoices.findDetailedById(receipt.getInvoiceId())
                .orElseThrow(() -> new IllegalStateException("Invoice of " + receipt.getDocumentNumber() + " not found"));
        Map<UUID, SalesInvoiceLine> invoiceLines = new HashMap<>();
        invoice.getLines().forEach(l -> invoiceLines.put(l.getId(), l));
        List<EbmRequests.Line> lines = new ArrayList<>();
        for (CreditNoteLine credit : creditLines.findByCreditNoteIdOrderByLineNo(note.getId())) {
            SalesInvoiceLine line = invoiceLines.get(credit.getInvoiceLineId());
            BigDecimal quantity;
            if (line.isServiceLine()) {
                // A processing is credited for the pieces of its size that come back
                quantity = line.getServiceQuantity().multiply(BigDecimal.valueOf(credit.getQuantity()))
                        .divide(BigDecimal.valueOf(line.getParentLine().getQuantity()), 4, RoundingMode.HALF_UP);
            } else {
                quantity = line.getChargeableAreaM2().multiply(BigDecimal.valueOf(credit.getQuantity()));
            }
            lines.add(line(line, credit.getAmount(), quantity, config, client, note.getPostedBy()));
        }
        Set<PaymentMethod> methods = EnumSet.noneOf(PaymentMethod.class);
        if (note.getRefundMethod() != null && note.getRefundAmount().signum() > 0) {
            methods.add(note.getRefundMethod());
        }
        if (note.getBalanceReduced().signum() > 0) {
            methods.add(PaymentMethod.CREDIT);
        }
        return new EbmRequests.Document(receipt.getInvcNo(), receipt.getOrgInvcNo(), EbmCodes.REFUND, invoice.getBuyerTin(),
                invoice.getBillTo(), invoice.getCustomer().getPhone(), null, EbmCodes.paymentType(methods, false),
                note.getPostedAt(), note.getCreditDate(),
                note.getRefundReason() != null ? note.getRefundReason() : EbmCodes.DEFAULT_REFUND_REASON,
                reportNo(config, client, note.getPostedAt()), note.getPostedBy(), config.companyName(), config.companyAddress(),
                rates(), lines);
    }

    private EbmRequests.Line line(SalesInvoiceLine line, BigDecimal amount, BigDecimal quantity, EbmSettings.Config config,
                                  VsdcClient client, String user) {
        if (line.isServiceLine()) {
            ProcessingService service = line.getService();
            String unit = EbmCodes.quantityUnit(service.getChargeUnit());
            String code = registered(null, service.getId(), required(config.serviceItemClass(), SettingKey.EBM_SERVICE_ITEM_CLASS),
                    EbmCodes.SERVICE_ITEM_TYPE, service.getName(), unit, line.getTaxCode(), line.getServiceUnitPrice(), config, client, user);
            return new EbmRequests.Line(code, config.serviceItemClass(), service.getName(), unit, quantity, line.getTaxCode(),
                    line.getVatRate(), amount);
        }
        Product product = line.getProduct();
        String name = glassName(product);
        String code = registered(product.getId(), null, required(config.glassItemClass(), SettingKey.EBM_GLASS_ITEM_CLASS),
                EbmCodes.GLASS_ITEM_TYPE, name, EbmCodes.GLASS_UNIT, line.getTaxCode(), line.getPricePerM2(), config, client, user);
        String size = line.getWidthMm() + " x " + line.getHeightMm() + (line.isCustomPiece() ? " x " + line.getQuantity() : "");
        return new EbmRequests.Line(code, config.glassItemClass(), name + " " + size, EbmCodes.GLASS_UNIT, quantity, line.getTaxCode(),
                line.getVatRate(), amount);
    }

    /**
     * The item's code, registering it with this VSDC first when it is not, or was registered with another classification,
     * tax letter or unit (saveItems replaces an item by its code). Its code is made once and kept in both modes.
     */
    private String registered(UUID productId, UUID serviceId, String itemClass, String itemType, String name, String unit,
                              String taxCode, BigDecimal price, EbmSettings.Config config, VsdcClient client, String user) {
        Optional<EbmItem> current = productId != null ? items.findByProductIdAndSimulated(productId, client.simulated())
                : items.findByServiceIdAndSimulated(serviceId, client.simulated());
        if (current.isPresent() && current.get().matches(itemClass, taxCode, unit)) {
            return current.get().getItemCode();
        }
        String code = current.map(EbmItem::getItemCode)
                .or(() -> (productId != null ? items.findByProductId(productId) : items.findByServiceId(serviceId)).stream()
                        .map(EbmItem::getItemCode).findFirst())
                .orElseGet(() -> EbmCodes.itemCode(config.origin(), itemType, EbmCodes.PACKAGING, unit, items.nextSerial()));
        Vsdc.ItemRequest request = EbmRequests.item(config.tin(), config.branchId(),
                new EbmRequests.Item(code, itemClass, itemType, name, config.origin(), unit, taxCode, price), user);
        Vsdc.Reply<JsonNode> reply = client.saveItem(request);
        switch (VsdcResults.of(reply.code())) {
            case SIGNED -> {
            }
            case RETRY -> throw new VsdcUnavailableException("Registering item " + code + ": " + reply.code() + " " + reply.message(),
                    reply.requestJson());
            default -> throw new ItemRefused("Item " + code + " (" + name + ") refused: " + reply.code() + " " + reply.message());
        }
        EbmItem item = current.orElseGet(EbmItem::new);
        if (current.isEmpty()) {
            item.setItemCode(code);
            item.setProductId(productId);
            item.setServiceId(serviceId);
            item.setSimulated(client.simulated());
        }
        item.setItemClass(itemClass);
        item.setTaxCode(taxCode);
        item.setQtyUnit(unit);
        item.setRegisteredAt(LocalDateTime.now(clock));
        items.save(item);
        return code;
    }

    /**
     * The day's report number: days since the device was initialised (or, without our initialisation, since the first
     * receipt), the first day being 1.
     */
    private long reportNo(EbmSettings.Config config, VsdcClient client, LocalDateTime at) {
        LocalDateTime start = config.deviceSerial() == null ? null
                : devices.findByTinAndBranchIdAndDeviceSerialAndSimulated(config.tin(), config.branchId(), config.deviceSerial(),
                        client.simulated()).map(EbmDevice::getInitialisedAt).orElse(null);
        if (start == null) {
            start = receipts.firstReceiptAt();
        }
        if (start == null || at.isBefore(start)) {
            return 1;
        }
        return ChronoUnit.DAYS.between(start.toLocalDate(), at.toLocalDate()) + 1;
    }

    /** The rate of each EBM letter (the header carries A to D), from the enabled tax categories. */
    private Map<String, BigDecimal> rates() {
        Map<String, BigDecimal> rates = new HashMap<>();
        for (TaxCategory t : taxCategories.findAllByOrderByEnabledDescCodeAsc()) {
            if (t.isEnabled()) {
                rates.putIfAbsent(t.getEbmCode(), t.getRate());
            }
        }
        return rates;
    }

    private static String required(String value, SettingKey key) {
        if (value == null) {
            throw new MissingSetting(key.key());
        }
        return value;
    }

    private static List<SalesInvoiceLine> sorted(List<SalesInvoiceLine> lines) {
        return lines.stream().sorted(Comparator.comparingInt(SalesInvoiceLine::getLineNo)).toList();
    }

    /** CLR-6 CLEAR 6 mm, TNT-6 TINTED Bronze 6 mm. */
    static String glassName(Product p) {
        return p.getCode() + " " + p.getGlassType().name() + (p.getVariant() == null ? "" : " " + p.getVariant())
                + " " + p.getThicknessLabel() + " mm";
    }
}
