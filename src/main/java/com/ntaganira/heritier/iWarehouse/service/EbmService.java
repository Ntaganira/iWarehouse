package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.ebm.EbmCodes;
import com.ntaganira.heritier.iWarehouse.ebm.Vsdc;
import com.ntaganira.heritier.iWarehouse.ebm.VsdcClient;
import com.ntaganira.heritier.iWarehouse.ebm.VsdcResults;
import com.ntaganira.heritier.iWarehouse.ebm.VsdcUnavailableException;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.EbmMode;
import com.ntaganira.heritier.iWarehouse.enums.EbmReceiptStatus;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import com.ntaganira.heritier.iWarehouse.repository.EbmDeviceRepository;
import com.ntaganira.heritier.iWarehouse.repository.EbmReceiptRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : EbmService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : EBM fiscal signing (TAX-02, TAX-03, POS-07, AT-09).
 *               - Issuing an invoice or a credit note queues its receipt in the same transaction (queueSale, queueRefund:
 *                 MANDATORY), with the next EBM invoice number; it is signed right after the commit (EbmWorker), so a sale
 *                 completes whether EBM answers or not, and the queue retries it. A credit note on an invoice issued
 *                 before the EBM connection has nothing to refund in EBM and is not queued.
 *               - Prints: the first print of a signed receipt is the original, every later one a copy (CS / CR).
 *               - A person may retry an unsigned receipt, correct a sale's purchase code, initialise the device (its last
 *                 invoice number moves ours up) and, with the simulator, switch an outage on.
 *               - The backlog: receipts unsigned longer than app.ebm.backlog-alert-after (1 h) raise one alert
 *                 (EBM_BACKLOG) until none is left.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class EbmService {

    static final String BACKLOG_ALERT = "EBM_BACKLOG";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final EbmReceiptRepository repo;
    private final EbmDeviceRepository devices;
    private final AlertStateRepository alertStates;
    private final DocumentNumberService numbers;
    private final EbmSettings ebmSettings;
    private final ApplicationEventPublisher events;
    private final Notifier notifier;
    private final Clock clock;

    public EbmService(EbmReceiptRepository repo, EbmDeviceRepository devices, AlertStateRepository alertStates,
                      DocumentNumberService numbers, EbmSettings ebmSettings, ApplicationEventPublisher events, Notifier notifier,
                      Clock clock) {
        this.repo = repo;
        this.devices = devices;
        this.alertStates = alertStates;
        this.numbers = numbers;
        this.ebmSettings = ebmSettings;
        this.events = events;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** A receipt to try at once, after the transaction that queued it commits. */
    public record Queued(UUID receiptId) {
    }

    /** What a print of a document is. */
    public enum Print {
        ORIGINAL, COPY, UNSIGNED
    }

    /** Receipts waiting for a signature and the oldest of them (TAX-03). */
    public record Backlog(long count, LocalDateTime oldest) {
    }

    /** The EBM page's header: mode, queue, settings still missing, the device, the simulator's outage. */
    public record Summary(EbmMode mode, long queued, long failed, LocalDateTime oldest, List<String> incomplete, EbmDevice device,
                          boolean outage) {

        public long getUnsigned() {
            return queued + failed;
        }

        public boolean isSimulated() {
            return mode == EbmMode.SIMULATOR;
        }
    }

    // ------------------------------------------------------------------ queue

    /** Queues an issued invoice's sale receipt (TAX-02): inside the transaction that issues it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public EbmReceipt queueSale(SalesInvoice invoice) {
        EbmReceipt receipt = new EbmReceipt();
        receipt.setInvcNo(numbers.nextSerial(DocumentType.EBM_INVOICE));
        receipt.setReceiptType(EbmCodes.SALE);
        receipt.setInvoiceId(invoice.getId());
        receipt.setDocumentNumber(invoice.getNumber());
        receipt.setPurchaseCode(invoice.getPurchaseCode());
        return queue(receipt);
    }

    /**
     * Queues a credit note's refund receipt, naming its invoice's EBM number; nothing when the invoice was issued before the
     * EBM connection (EBM never had the sale).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<EbmReceipt> queueRefund(CreditNote note) {
        Optional<EbmReceipt> sale = repo.findSale(note.getInvoice().getId());
        if (sale.isEmpty()) {
            return Optional.empty();
        }
        EbmReceipt receipt = new EbmReceipt();
        receipt.setInvcNo(numbers.nextSerial(DocumentType.EBM_INVOICE));
        receipt.setReceiptType(EbmCodes.REFUND);
        receipt.setInvoiceId(note.getInvoice().getId());
        receipt.setCreditNoteId(note.getId());
        receipt.setDocumentNumber(note.getNumber());
        receipt.setOrgInvcNo(sale.get().getInvcNo());
        return Optional.of(queue(receipt));
    }

    private EbmReceipt queue(EbmReceipt receipt) {
        receipt.setStatus(EbmReceiptStatus.QUEUED);
        receipt.setNextAttemptAt(LocalDateTime.now(clock));
        EbmReceipt saved = repo.save(receipt);
        events.publishEvent(new Queued(saved.getId()));
        return saved;
    }

    /** Receipts due for an attempt, oldest number first. */
    public List<UUID> due(int limit) {
        return repo.findDue(LocalDateTime.now(clock), PageRequest.of(0, limit));
    }

    // ------------------------------------------------------------------ reading

    public Optional<EbmReceipt> ofInvoice(UUID invoiceId) {
        return repo.findSale(invoiceId);
    }

    public Optional<EbmReceipt> ofCreditNote(UUID creditNoteId) {
        return repo.findByCreditNoteId(creditNoteId);
    }

    public EbmReceipt find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("EbmReceipt", id));
    }

    /** What the receipt's QR code holds (RRA's receipt check); null until signed. */
    public String qrData(EbmReceipt receipt) {
        if (receipt == null || !receipt.isSigned()) {
            return null;
        }
        EbmSettings.Config config = ebmSettings.config();
        return EbmCodes.qrData(config.receiptUrl(), config.tin() == null ? "" : config.tin(), config.branchId(), receipt.getRcptSign());
    }

    /** "unsigned" (queued or failed), "failed", "signed" or everything; a document number or EBM number. Newest first. */
    public Page<EbmReceipt> findPage(String show, String search, int page, int size) {
        Specification<EbmReceipt> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if ("unsigned".equals(show)) {
                where.add(cb.notEqual(root.get("status"), EbmReceiptStatus.SIGNED));
            } else if ("failed".equals(show)) {
                where.add(cb.equal(root.get("status"), EbmReceiptStatus.FAILED));
            } else if ("signed".equals(show)) {
                where.add(cb.equal(root.get("status"), EbmReceiptStatus.SIGNED));
            }
            if (search != null && !search.isBlank()) {
                String term = search.trim();
                Predicate number = cb.like(root.get("documentNumber"), "%" + term.toUpperCase(Locale.ROOT) + "%");
                where.add(term.matches("\\d{1,18}") ? cb.or(number, cb.equal(root.get("invcNo"), Long.parseLong(term))) : number);
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "invcNo")));
    }

    public Backlog backlog() {
        long unsigned = repo.countByStatus(EbmReceiptStatus.QUEUED) + repo.countByStatus(EbmReceiptStatus.FAILED);
        return new Backlog(unsigned, unsigned == 0 ? null : repo.oldestUnsigned());
    }

    public Summary summary() {
        EbmSettings.Config config = ebmSettings.config();
        return new Summary(config.mode(), repo.countByStatus(EbmReceiptStatus.QUEUED), repo.countByStatus(EbmReceiptStatus.FAILED),
                repo.oldestUnsigned(), config.incomplete(), device(config).orElse(null), config.simulated() && ebmSettings.simulator().isDown());
    }

    private Optional<EbmDevice> device(EbmSettings.Config config) {
        String serial = serial(config);
        if (config.tin() == null || serial == null) {
            return Optional.empty();
        }
        return devices.findByTinAndBranchIdAndDeviceSerialAndSimulated(config.tin(), config.branchId(), serial, config.simulated());
    }

    /** The device serial sent: the setting's, or a fixed one for the simulator. */
    private static String serial(EbmSettings.Config config) {
        return config.deviceSerial() != null ? config.deviceSerial() : config.simulated() ? "SIMULATOR" : null;
    }

    // ------------------------------------------------------------------ prints

    /** Records a print of the invoice's receipt: the original the first time, a copy afterwards. */
    @Transactional
    public Print printInvoice(UUID invoiceId) {
        return repo.findSale(invoiceId).map(r -> print(r.getId())).orElse(Print.UNSIGNED);
    }

    @Transactional
    public Print printCreditNote(UUID creditNoteId) {
        return repo.findByCreditNoteId(creditNoteId).map(r -> print(r.getId())).orElse(Print.UNSIGNED);
    }

    private Print print(UUID receiptId) {
        EbmReceipt receipt = repo.lockById(receiptId).orElseThrow(() -> new NotFoundException("EbmReceipt", receiptId));
        if (!receipt.isSigned()) {
            return Print.UNSIGNED;
        }
        if (receipt.getPrintedAt() == null) {
            receipt.setPrintedAt(LocalDateTime.now(clock));
            return Print.ORIGINAL;
        }
        receipt.setCopies(receipt.getCopies() + 1);
        return Print.COPY;
    }

    // ------------------------------------------------------------------ actions

    /** Sends an unsigned receipt again now (a failed one once its cause is fixed). */
    @Transactional
    public EbmReceipt retry(UUID id) {
        EbmReceipt receipt = repo.lockById(id).orElseThrow(() -> new NotFoundException("EbmReceipt", id));
        if (receipt.isSigned()) {
            throw BusinessException.of("ebm.retry.signed", receipt.getDocumentNumber());
        }
        receipt.setNextAttemptAt(LocalDateTime.now(clock));
        receipt.setStatus(EbmReceiptStatus.QUEUED);
        events.publishEvent(new Queued(receipt.getId()));
        return receipt;
    }

    /** Makes every unsigned receipt due now; the worker sends them within its next run. How many. */
    @Transactional
    public int retryAll() {
        List<UUID> ids = repo.findUnsignedIds();
        LocalDateTime now = LocalDateTime.now(clock);
        for (UUID id : ids) {
            repo.lockById(id).filter(r -> !r.isSigned()).ifPresent(r -> {
                r.setNextAttemptAt(now);
                r.setStatus(EbmReceiptStatus.QUEUED);
            });
        }
        return ids.size();
    }

    /** Corrects the buyer's purchase code of an unsigned sale (VSDC 881-883) and sends it again. */
    @Transactional
    public EbmReceipt changePurchaseCode(UUID id, String purchaseCode) {
        EbmReceipt receipt = repo.lockById(id).orElseThrow(() -> new NotFoundException("EbmReceipt", id));
        if (receipt.isSigned() || receipt.isRefund()) {
            throw BusinessException.of("ebm.purchaseCode.locked", receipt.getDocumentNumber());
        }
        String code = purchaseCode == null || purchaseCode.isBlank() ? null : purchaseCode.trim();
        if (code != null && !code.matches("[A-Za-z0-9]{1,6}")) {
            throw BusinessException.onField("purchaseCode", "sale.purchaseCode.invalid");
        }
        receipt.setPurchaseCode(code);
        receipt.setNextAttemptAt(LocalDateTime.now(clock));
        receipt.setStatus(EbmReceiptStatus.QUEUED);
        events.publishEvent(new Queued(receipt.getId()));
        return receipt;
    }

    /**
     * Initialises the EBM device with the VSDC (TIN, branch, device serial from Settings) and records what RRA returned.
     * A device installed before (902) is recorded without details. Our EBM invoice numbers go on after RRA's last one.
     */
    @Transactional
    public EbmDevice initialise() {
        EbmSettings.Config config = ebmSettings.config();
        List<String> missing = new ArrayList<>(config.missing());
        String serial = serial(config);
        if (serial == null) {
            missing.add(SettingKey.EBM_DEVICE_SERIAL.key());
        }
        if (!missing.isEmpty()) {
            throw BusinessException.of("ebm.device.settings", String.join(", ", missing));
        }
        VsdcClient client = ebmSettings.client(config);
        Vsdc.Reply<Vsdc.InitData> reply;
        try {
            reply = client.init(new Vsdc.InitRequest(config.tin(), config.branchId(), serial));
        } catch (VsdcUnavailableException e) {
            throw BusinessException.of("ebm.device.unreachable", e.getMessage());
        }
        boolean installed = "902".equals(reply.code());
        Vsdc.InitInfo info = reply.data() == null ? null : reply.data().info();
        if (!installed && (VsdcResults.of(reply.code()) != VsdcResults.Outcome.SIGNED || info == null)) {
            throw BusinessException.of("ebm.device.refused", reply.code(), reply.message());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        EbmDevice device = devices.findByTinAndBranchIdAndDeviceSerialAndSimulated(config.tin(), config.branchId(), serial,
                client.simulated()).orElseGet(() -> {
            EbmDevice d = new EbmDevice();
            d.setTin(config.tin());
            d.setBranchId(config.branchId());
            d.setDeviceSerial(serial);
            d.setSimulated(client.simulated());
            d.setInitialisedAt(now);
            return d;
        });
        device.setAlreadyInstalled(installed);
        if (info != null) {
            device.setTaxpayerName(EbmCodes.cut(info.taxprNm(), 60));
            device.setBranchName(EbmCodes.cut(info.bhfNm(), 60));
            device.setDeviceId(EbmCodes.cut(info.dvcId(), 20));
            device.setSdcId(EbmCodes.cut(info.sdcId(), 20));
            device.setMrcNo(EbmCodes.cut(info.mrcNo(), 20));
            device.setLastInvcNo(info.lastInvcNo());
            device.setLastSaleRcptNo(info.lastSaleRcptNo());
            if (info.lastInvcNo() != null) {
                numbers.raiseTo(DocumentType.EBM_INVOICE, info.lastInvcNo() + 1);
            }
        }
        return devices.save(device);
    }

    /** Switches the simulator's outage on or off (AT-09); refused unless the simulator signs. */
    public boolean simulateOutage(boolean down) {
        if (!ebmSettings.config().simulated()) {
            throw BusinessException.of("ebm.outage.notSimulator");
        }
        ebmSettings.simulator().setDown(down);
        return down;
    }

    // ------------------------------------------------------------------ backlog alert

    /**
     * Raises the backlog alert once receipts have waited longer than {@code after} for their signature (TAX-03, NFR-17),
     * and clears it when none has. True when it was raised now.
     */
    @Transactional
    public boolean checkBacklog(Duration after) {
        LocalDateTime now = LocalDateTime.now(clock);
        long late = repo.countUnsignedBefore(now.minus(after));
        AlertState state = alertStates.findById(BACKLOG_ALERT).orElse(null);
        boolean raised = state != null && state.getClearedAt() == null;
        if (late > 0 && !raised) {
            AlertState s = state != null ? state : new AlertState();
            s.setKey(BACKLOG_ALERT);
            s.setRaisedAt(now);
            s.setClearedAt(null);
            alertStates.save(s);
            LocalDateTime oldest = repo.oldestUnsigned();
            notifier.holders("ALERT_EBM", null, NotificationKind.EBM, "notify.ebm.backlog.title", "notify.ebm.backlog.message",
                    "/ebm?show=unsigned", String.valueOf(late), oldest == null ? "" : oldest.format(WHEN));
            return true;
        }
        if (late == 0 && state != null && raised) {
            state.setClearedAt(now);
        }
        return false;
    }
}
