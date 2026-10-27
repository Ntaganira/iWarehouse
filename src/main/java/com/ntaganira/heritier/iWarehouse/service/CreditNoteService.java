package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.CreditNoteDto;
import com.ntaganira.heritier.iWarehouse.ebm.EbmCodes;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CreditNoteService.java
 * - Date      : 2026. 10. 09.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Returns and credit notes (POS-09). The customer brings back units taken on an issued invoice: sheets sold
 *               from stock and pieces handed over, each once. Each goes back on a rack (available, at its own cost: the
 *               MAC moves) or to cullet (BROKEN). An order's pieces not handed over yet can be given up instead (CANCEL):
 *               pieces not cut come off the draft cutting jobs, pieces cut and reserved for it are released to stock;
 *               pieces on a sheet being cut wait for the cut. Each invoice line is credited its share of the pieces
 *               (CreditNotes.share, counting every credit note before), VAT per tax letter. The credit first reduces the
 *               invoice's balance due; the rest is refunded in cash from the user's till (if it holds that much), by
 *               mobile money, card or transfer, or to the customer's account (not for walk-ins). Issuing locks the till
 *               (cash refunds), then the invoice, then the products, and posts the journal. When Settings ask for it
 *               (F-14), a credit note is first asked (CreditNoteRequest: what comes back or is given up, the refund, the
 *               totals), reviewed by Finance, approved by another person within the Delegation of Authority, and only then
 *               issued, never for more than was approved; nobody who asked or reviewed approves it.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class CreditNoteService {

    private final CreditNoteRepository repo;
    private final CreditNoteLineRepository lineRepo;
    private final CreditNoteUnitRepository returnedRepo;
    private final SalesInvoiceRepository invoiceRepo;
    private final SalesDeliveryRepository deliveryRepo;
    private final StockUnitRepository unitRepo;
    private final ProductRepository productRepo;
    private final CuttingJobRepository jobRepo;
    private final CuttingJobLineRepository jobLineRepo;
    private final SalesService salesService;
    private final CuttingJobService cuttingJobService;
    private final TillService tillService;
    private final StockService stockService;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final EbmService ebmService;
    private final Clock clock;
    private final CreditNoteRequestRepository requestRepo;
    private final DelegationOfAuthority authority;
    private final SettingService settingService;
    private final Notifier notifier;

    public CreditNoteService(CreditNoteRepository repo, CreditNoteLineRepository lineRepo, CreditNoteUnitRepository returnedRepo,
                             SalesInvoiceRepository invoiceRepo, SalesDeliveryRepository deliveryRepo, StockUnitRepository unitRepo,
                             ProductRepository productRepo, CuttingJobRepository jobRepo, CuttingJobLineRepository jobLineRepo,
                             SalesService salesService, CuttingJobService cuttingJobService, TillService tillService,
                             StockService stockService, PostingService postingService, DocumentNumberService numbers,
                             EbmService ebmService, Clock clock, CreditNoteRequestRepository requestRepo, DelegationOfAuthority authority,
                             SettingService settingService, Notifier notifier) {
        this.requestRepo = requestRepo;
        this.authority = authority;
        this.settingService = settingService;
        this.notifier = notifier;
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.returnedRepo = returnedRepo;
        this.invoiceRepo = invoiceRepo;
        this.deliveryRepo = deliveryRepo;
        this.unitRepo = unitRepo;
        this.productRepo = productRepo;
        this.jobRepo = jobRepo;
        this.jobLineRepo = jobLineRepo;
        this.salesService = salesService;
        this.cuttingJobService = cuttingJobService;
        this.tillService = tillService;
        this.stockService = stockService;
        this.postingService = postingService;
        this.numbers = numbers;
        this.ebmService = ebmService;
        this.clock = clock;
    }

    /**
     * A unit the customer took on an invoice and can bring back: its invoice line (the sheet's, or the size it was cut for),
     * the line's pieces and how many were credited before (brought back or given up), the amounts credited per piece (the
     * line's and its processing's) and about what one piece is worth.
     */
    public record Returnable(StockUnit unit, SalesInvoiceLine line, int quantity, int returned, List<BigDecimal> amounts) {

        /** One piece's share of the line and its processing, whole RWF. */
        public BigDecimal getValueEach() {
            return valueEach(amounts, quantity);
        }

        /** The amounts, for the form's running total: "13500.00,6000.00". */
        public String getAmountList() {
            return amountList(amounts);
        }
    }

    /**
     * A size of an order and its pieces not handed over (CANCEL): ordered, handed over, given up before, credited before
     * (for the share), the pieces cut and waiting for the customer, those on draft cutting jobs and those on a sheet being
     * cut, and the amounts credited per piece.
     */
    public record Cancellable(SalesInvoiceLine line, int ordered, int delivered, int cancelled, int credited, List<StockUnit> ready,
                              int drafted, int cutting, List<BigDecimal> amounts) {

        public int getRemaining() {
            return Math.max(ordered - delivered - cancelled, 0);
        }

        /** Not cut and on no job (a job cut fewer than asked, or broke some): given up first. */
        public int getUnplanned() {
            return Math.max(getRemaining() - ready.size() - drafted - cutting, 0);
        }

        /** What can be given up now: everything not handed over but the pieces on a sheet being cut. */
        public int getCancellableNow() {
            return Math.min(getRemaining(), getUnplanned() + drafted + ready.size());
        }

        public BigDecimal getValueEach() {
            return valueEach(amounts, ordered);
        }

        public String getAmountList() {
            return amountList(amounts);
        }
    }

    private static BigDecimal valueEach(List<BigDecimal> amounts, int quantity) {
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(quantity), 0, RoundingMode.HALF_UP);
    }

    private static String amountList(List<BigDecimal> amounts) {
        return amounts.stream().map(BigDecimal::toPlainString).collect(Collectors.joining(","));
    }

    // ---------------------------------------------------------------- reading

    public CreditNote findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("CreditNote", id));
    }

    public List<CreditNoteLine> lines(UUID creditNoteId) {
        return lineRepo.findByCreditNoteIdOrderByLineNo(creditNoteId);
    }

    public List<CreditNoteUnit> units(UUID creditNoteId) {
        return returnedRepo.findByCreditNoteIdOrderByUnitCode(creditNoteId);
    }

    /** The credit notes of an invoice, oldest first. */
    public List<CreditNote> ofInvoice(UUID invoiceId) {
        return repo.findByInvoice_IdOrderByNumberAsc(invoiceId);
    }

    /** The cash refunds made from a till session. */
    public List<CreditNote> refundsOf(TillSession session) {
        return repo.findByTillSessionIdOrderByPostedAtAsc(session.getId());
    }

    /** Units brought back per invoice line (the sheet's, or the size's). */
    public Map<UUID, Integer> returnedCounts(UUID invoiceId) {
        Map<UUID, Integer> counts = new HashMap<>();
        returnedRepo.findByInvoiceId(invoiceId).stream().filter(u -> u.getOutcome() != ReturnOutcome.RELEASE)
                .forEach(u -> counts.merge(u.getInvoiceLineId(), 1, Integer::sum));
        return counts;
    }

    /** Pieces credited per glass line of an invoice, brought back or given up: the share of the next ones starts there. */
    private Map<UUID, Integer> creditedCounts(SalesInvoice invoice) {
        List<UUID> glass = invoice.getLines().stream().filter(l -> !l.isServiceLine()).map(SalesInvoiceLine::getId)
                .filter(Objects::nonNull).toList();
        Map<UUID, Integer> counts = new HashMap<>();
        if (!glass.isEmpty()) {
            for (Object[] row : lineRepo.creditedPieces(glass)) {
                counts.put((UUID) row[0], ((Number) row[1]).intValue());
            }
        }
        return counts;
    }

    /** Credit notes, newest first. */
    public Page<CreditNote> findPage(String search, int page, int size) {
        Specification<CreditNote> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(root.get("invoice").get("number")), term),
                        cb.like(cb.lower(root.get("customer").get("name")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("invoice").get("buyerName"), "")), term),
                        cb.like(cb.lower(root.get("reason")), term),
                        cb.like(cb.lower(root.get("postedBy")), term)));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "postedAt")));
    }

    /**
     * What the customer can bring back from an issued invoice: its sheets from stock and the pieces handed over, still
     * sold and not brought back already, by line then label.
     */
    public List<Returnable> returnables(SalesInvoice invoice) {
        if (invoice.getStatus() != SalesInvoiceStatus.POSTED) {
            return List.of();
        }
        Set<UUID> back = returnedRepo.findByInvoiceId(invoice.getId()).stream().map(CreditNoteUnit::getStockUnitId)
                .collect(Collectors.toSet());
        Map<UUID, Integer> credited = creditedCounts(invoice);
        Map<UUID, SalesInvoiceLine> lines = invoice.getLines().stream().filter(l -> l.getId() != null)
                .collect(Collectors.toMap(SalesInvoiceLine::getId, Function.identity()));
        // unit id -> the line it was taken on
        Map<UUID, SalesInvoiceLine> taken = new LinkedHashMap<>();
        invoice.getLines().stream().filter(SalesInvoiceLine::isStockUnit).forEach(l -> taken.put(l.getStockUnitId(), l));
        deliveryRepo.findByInvoiceIdOrderByDeliveredAtAscUnitCodeAsc(invoice.getId())
                .forEach(d -> taken.put(d.getStockUnitId(), lines.get(d.getLineId())));
        taken.keySet().removeAll(back);
        if (taken.isEmpty()) {
            return List.of();
        }
        List<Returnable> items = new ArrayList<>();
        for (StockUnit unit : unitRepo.findAllById(taken.keySet())) {
            if (unit.getStatus() != StockStatus.SOLD) {
                continue;
            }
            SalesInvoiceLine line = taken.get(unit.getId());
            items.add(new Returnable(unit, line, line.getQuantity(), credited.getOrDefault(line.getId(), 0), amountsOf(invoice, line)));
        }
        items.sort(Comparator.comparing((Returnable r) -> r.line().getLineNo()).thenComparing(r -> r.unit().getCode()));
        return items;
    }

    /** A size's amounts credited per piece: its own and each of its processing's. */
    private static List<BigDecimal> amountsOf(SalesInvoice invoice, SalesInvoiceLine line) {
        List<BigDecimal> amounts = new ArrayList<>();
        amounts.add(line.getAmount());
        invoice.getLines().stream().filter(l -> l.getParentLine() == line).forEach(l -> amounts.add(l.getAmount()));
        return amounts;
    }

    /** A blank return form for an invoice: every unit it can take back, none ticked, each back to stock. */
    public CreditNoteDto newForm(SalesInvoice invoice) {
        CreditNoteDto dto = new CreditNoteDto();
        dto.setInvoiceId(invoice.getId());
        for (Returnable r : returnables(invoice)) {
            CreditNoteDto.Item item = new CreditNoteDto.Item();
            item.setUnitId(r.unit().getId());
            dto.getItems().add(item);
        }
        return dto;
    }

    /**
     * The sizes of an issued invoice with pieces not handed over, by line: what is cut and waiting, what the draft cutting
     * jobs still have to cut, what is on a sheet being cut.
     */
    public List<Cancellable> cancellables(SalesInvoice invoice) {
        if (invoice.getStatus() != SalesInvoiceStatus.POSTED || invoice.getLines().stream().noneMatch(SalesInvoiceLine::isCustomPiece)) {
            return List.of();
        }
        Map<UUID, SalesService.Progress> progress = salesService.progress(invoice);
        Map<UUID, Integer> credited = creditedCounts(invoice);
        // Pieces cut for the invoice and waiting, by size
        Map<UUID, List<StockUnit>> ready = new HashMap<>();
        Map<UUID, UUID> sizeOfPiece = salesService.piecesOf(invoice);
        salesService.readyPieces(invoice).forEach(u -> ready.computeIfAbsent(sizeOfPiece.get(u.getId()), k -> new ArrayList<>()).add(u));
        // Pieces still to cut on the invoice's open jobs, by size and job status
        Map<UUID, Integer> drafted = new HashMap<>();
        Map<UUID, Integer> cutting = new HashMap<>();
        List<CuttingJob> jobs = jobRepo.findBySalesInvoiceIdOrderByNumberAsc(invoice.getId());
        Map<UUID, CuttingJobStatus> statusOf = jobs.stream().collect(Collectors.toMap(CuttingJob::getId, CuttingJob::getStatus));
        if (!jobs.isEmpty()) {
            for (CuttingJobLine l : jobLineRepo.findByJob_IdIn(statusOf.keySet())) {
                if (l.getSalesLineId() == null) {
                    continue;
                }
                CuttingJobStatus status = statusOf.get(l.getJob().getId());
                if (status == CuttingJobStatus.DRAFT) {
                    drafted.merge(l.getSalesLineId(), l.getQuantity(), Integer::sum);
                } else if (status == CuttingJobStatus.IN_PROGRESS) {
                    cutting.merge(l.getSalesLineId(), l.getQuantity(), Integer::sum);
                }
            }
        }
        List<Cancellable> sizes = new ArrayList<>();
        for (SalesInvoiceLine line : invoice.getLines()) {
            if (!line.isCustomPiece()) {
                continue;
            }
            SalesService.Progress p = progress.get(line.getId());
            Cancellable c = new Cancellable(line, p.ordered(), p.delivered(), p.cancelled(), credited.getOrDefault(line.getId(), 0),
                    ready.getOrDefault(line.getId(), List.of()), drafted.getOrDefault(line.getId(), 0), cutting.getOrDefault(line.getId(), 0),
                    amountsOf(invoice, line));
            if (c.getRemaining() > 0) {
                sizes.add(c);
            }
        }
        return sizes;
    }

    /** A blank cancel form for an order: each size with pieces not handed over, none given up. */
    public CreditNoteDto newCancelForm(SalesInvoice invoice) {
        CreditNoteDto dto = new CreditNoteDto();
        dto.setInvoiceId(invoice.getId());
        for (Cancellable c : cancellables(invoice)) {
            CreditNoteDto.Size size = new CreditNoteDto.Size();
            size.setLineId(c.line().getId());
            dto.getSizes().add(size);
        }
        return dto;
    }

    // ---------------------------------------------------------------- issuing (POS-09)

    /**
     * Issues the credit note of a return: checks what comes back and where it goes, credits the lines, puts the units back
     * on the rack or to cullet (moving each glass's MAC with what came back), reduces the invoice's balance due, records
     * the refund and posts the journal.
     */
    @Transactional
    public CreditNote issue(CreditNoteDto form) {
        requireRequest(form);
        TillSession till = tillFor(form);
        SalesInvoice invoice = lockedInvoice(form.getInvoiceId());
        String reason = reason(form);
        String refundReason = refundReason(form);

        // What comes back, and where it goes
        ReturnPick pick = pickReturns(form, invoice);
        Map<Returnable, ReturnOutcome> chosen = pick.chosen();
        Location location = pick.location();

        Map<SalesInvoiceLine, Integer> pieces = new TreeMap<>(Comparator.comparingInt(SalesInvoiceLine::getLineNo));
        chosen.keySet().forEach(r -> pieces.merge(r.line(), 1, Integer::sum));
        List<CreditNoteLine> lines = creditLines(invoice, pieces);
        Vat.Totals totals = totals(lines);
        Refund refund = refund(form, invoice, totals, till);

        // The glass: products locked, their value read before anything moves
        List<StockUnit> units = chosen.keySet().stream().map(Returnable::unit).toList();
        Map<UUID, Product> products = productRepo.lockAllById(units.stream().map(u -> u.getProduct().getId())
                        .collect(Collectors.toCollection(TreeSet::new))).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<UUID, BigDecimal> heldBefore = new HashMap<>();
        products.keySet().forEach(id -> heldBefore.put(id, stockService.heldArea(id)));
        PostingService.StockValues before = postingService.stockValues(products.values());

        CreditNote note = save(CreditNoteKind.RETURN, invoice, reason, refundReason, totals, refund, till, form.getRequestId());
        BigDecimal restockedCost = BigDecimal.ZERO;
        BigDecimal culletCost = BigDecimal.ZERO;
        Map<UUID, BigDecimal> areaBack = new HashMap<>();
        Map<UUID, BigDecimal> valueBack = new HashMap<>();
        for (Map.Entry<Returnable, ReturnOutcome> e : chosen.entrySet()) {
            StockUnit unit = e.getKey().unit();
            if (e.getValue() == ReturnOutcome.RESTOCK) {
                stockService.returnToStock(unit, location, note.getId(), note.getNumber());
                restockedCost = restockedCost.add(unit.getUnitCost());
                areaBack.merge(unit.getProduct().getId(), unit.getAreaM2(), BigDecimal::add);
                valueBack.merge(unit.getProduct().getId(), unit.getUnitCost(), BigDecimal::add);
            } else {
                stockService.returnAsCullet(unit, note.getId(), note.getNumber());
                culletCost = culletCost.add(unit.getUnitCost());
            }
            saveUnit(note, invoice, e.getKey().line(), unit, e.getValue(), e.getValue() == ReturnOutcome.RESTOCK ? location : null);
        }
        // Glass back on the racks at its own cost moves the MAC (as a unit found again does)
        for (Map.Entry<UUID, BigDecimal> e : areaBack.entrySet()) {
            Product product = products.get(e.getKey());
            product.setMacPerM2(Costing.afterStockChange(heldBefore.get(e.getKey()), product.getMacPerM2(), e.getValue(),
                    valueBack.get(e.getKey())));
        }
        finish(note, invoice, lines, refund);
        postingService.creditNote(note, restockedCost, culletCost, before);
        return note;
    }

    /**
     * Gives up pieces of an order not handed over yet (CANCEL): per size at most what can go now. Pieces on no job go first,
     * then those of the draft cutting jobs (the jobs ask for fewer), then the pieces cut and waiting (released to stock where
     * they lie). Pieces on a sheet being cut wait for the cut. The sizes are credited, the balance due reduced, the rest
     * refunded; nothing left stock, so no cost moves.
     */
    @Transactional
    public CreditNote cancelPieces(CreditNoteDto form) {
        requireRequest(form);
        TillSession till = tillFor(form);
        SalesInvoice invoice = lockedInvoice(form.getInvoiceId());
        String reason = reason(form);
        String refundReason = refundReason(form);

        Map<Cancellable, Integer> chosen = pickSizes(form, invoice);

        Map<SalesInvoiceLine, Integer> pieces = new TreeMap<>(Comparator.comparingInt(SalesInvoiceLine::getLineNo));
        chosen.forEach((c, n) -> pieces.put(c.line(), n));
        List<CreditNoteLine> lines = creditLines(invoice, pieces);
        Vat.Totals totals = totals(lines);
        Refund refund = refund(form, invoice, totals, till);

        CreditNote note = save(CreditNoteKind.CANCEL, invoice, reason, refundReason, totals, refund, till, form.getRequestId());
        String why = "Given up on " + note.getNumber() + ": " + reason;
        for (Map.Entry<Cancellable, Integer> e : chosen.entrySet()) {
            Cancellable c = e.getKey();
            int left = e.getValue() - Math.min(e.getValue(), c.getUnplanned());
            if (left > 0) {
                left -= cuttingJobService.takeOffSale(invoice.getId(), c.line().getId(), left, why);
            }
            for (StockUnit piece : c.ready()) {
                if (left == 0) {
                    break;
                }
                Location place = piece.getLocation();
                stockService.release(piece, why);
                saveUnit(note, invoice, c.line(), piece, ReturnOutcome.RELEASE, place);
                left--;
            }
            if (left > 0) {
                throw new IllegalStateException("pieces left to give up on " + c.line().getId() + ": " + left);
            }
        }
        finish(note, invoice, lines, refund);
        postingService.creditNote(note, BigDecimal.ZERO, BigDecimal.ZERO, postingService.stockValues(List.of()));
        return note;
    }

    // ---------------------------------------------------------------- what comes back or is given up

    /** The units coming back and where they go, and the rack they go on (checked: limits, no sheet on an off-cut rack). */
    private record ReturnPick(Map<Returnable, ReturnOutcome> chosen, Location location) {
    }

    private ReturnPick pickReturns(CreditNoteDto form, SalesInvoice invoice) {
        Map<UUID, Returnable> returnable = returnables(invoice).stream()
                .collect(Collectors.toMap(r -> r.unit().getId(), Function.identity()));
        Map<Returnable, ReturnOutcome> chosen = new LinkedHashMap<>();
        for (CreditNoteDto.Item item : form.getItems()) {
            if (!item.isSelected()) {
                continue;
            }
            Returnable r = returnable.get(item.getUnitId());
            if (r == null) {
                String code = item.getUnitId() == null ? "" : unitRepo.findById(item.getUnitId()).map(StockUnit::getCode).orElse("");
                throw BusinessException.onField("items", "creditNote.unit.notReturnable", code, invoice.getNumber());
            }
            chosen.put(r, item.isCullet() ? ReturnOutcome.CULLET : ReturnOutcome.RESTOCK);
        }
        if (chosen.isEmpty()) {
            throw BusinessException.onField("items", "creditNote.items.required");
        }
        Location location = null;
        if (chosen.containsValue(ReturnOutcome.RESTOCK)) {
            Map<UUID, Location> byId = stockService.locationsById();
            location = form.getLocationId() == null ? null : stockService.storagePlaces(byId).stream()
                    .filter(l -> l.getId().equals(form.getLocationId())).findFirst().orElse(null);
            if (location == null) {
                throw BusinessException.onField("locationId", form.getLocationId() == null ? "creditNote.location.required"
                        : "creditNote.location.invalid");
            }
            checkRack(location, chosen.entrySet().stream().filter(e -> e.getValue() == ReturnOutcome.RESTOCK)
                    .map(e -> e.getKey().unit()).toList(), byId);
        }
        return new ReturnPick(chosen, location);
    }

    /** The pieces given up per size, at most what can go now. */
    private Map<Cancellable, Integer> pickSizes(CreditNoteDto form, SalesInvoice invoice) {
        Map<UUID, Cancellable> cancellable = cancellables(invoice).stream()
                .collect(Collectors.toMap(c -> c.line().getId(), Function.identity()));
        Map<Cancellable, Integer> chosen = new LinkedHashMap<>();
        for (int i = 0; i < form.getSizes().size(); i++) {
            CreditNoteDto.Size size = form.getSizes().get(i);
            int pieces = size.getQuantity() == null ? 0 : size.getQuantity();
            if (pieces == 0) {
                continue;
            }
            Cancellable c = cancellable.get(size.getLineId());
            String field = "sizes[" + i + "].quantity";
            if (c == null || pieces < 0) {
                throw BusinessException.onField(field, "creditNote.cancel.invalid");
            }
            if (pieces > c.getRemaining()) {
                throw BusinessException.onField(field, "creditNote.cancel.tooMany", pieces, c.getRemaining());
            }
            if (pieces > c.getCancellableNow()) {
                String job = jobRepo.findBySalesInvoiceIdOrderByNumberAsc(invoice.getId()).stream()
                        .filter(j -> j.getStatus() == CuttingJobStatus.IN_PROGRESS).map(CuttingJob::getNumber).findFirst().orElse("");
                throw BusinessException.onField(field, "creditNote.cancel.inCutting", c.getCancellableNow(), job);
            }
            chosen.put(c, pieces);
        }
        if (chosen.isEmpty()) {
            throw BusinessException.onField("sizes", "creditNote.cancel.required");
        }
        return chosen;
    }

    // ---------------------------------------------------------------- asked, reviewed, approved, issued (F-14)

    /** Whether credit notes are asked and approved before they are issued (Settings). */
    public boolean approvalRequired() {
        return settingService.getBoolean(SettingKey.APPROVAL_CREDIT_NOTE_REQUIRED);
    }

    public boolean reviewRequired() {
        return settingService.getBoolean(SettingKey.APPROVAL_CREDIT_NOTE_REVIEW);
    }

    /** With approval required, a credit note is issued only from its approved request. */
    private void requireRequest(CreditNoteDto form) {
        if (form.getRequestId() == null && approvalRequired()) {
            throw BusinessException.of("creditNote.request.required");
        }
    }

    public CreditNoteRequest findRequest(UUID id) {
        return requestRepo.findDetailedById(id).orElseThrow(() -> new NotFoundException("CreditNoteRequest", id));
    }

    public Page<CreditNoteRequest> findRequests(String status, String search, int page, int size) {
        Specification<CreditNoteRequest> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (StringUtils.hasText(status)) {
                try {
                    p.add(cb.equal(root.get("status"), CreditNoteRequestStatus.valueOf(status)));
                } catch (IllegalArgumentException ignored) {
                    // an unknown status filters nothing
                }
            }
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("invoice").get("number")), term),
                        cb.like(cb.lower(root.get("customer").get("name")), term), cb.like(cb.lower(root.get("reason")), term),
                        cb.like(cb.lower(root.get("requestedBy")), term)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return requestRepo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("number"))));
    }

    public long waitingRequests() {
        return requestRepo.countByStatusIn(List.of(CreditNoteRequestStatus.PENDING_REVIEW, CreditNoteRequestStatus.PENDING_APPROVAL,
                CreditNoteRequestStatus.APPROVED));
    }

    public DelegationOfAuthority.Check authorityOf(CreditNoteRequest r) {
        return authority.check(AuthorityDecision.CREDIT_NOTE, r.getTotalAmount());
    }

    /** The units of a return request, by label, with where each goes. */
    public List<StockUnit> requestUnits(CreditNoteRequest r) {
        List<UUID> ids = r.getItems().stream().map(CreditNoteRequest.Item::getUnitId).toList();
        return ids.isEmpty() ? List.of() : unitRepo.findByIdIn(ids).stream().sorted(Comparator.comparing(StockUnit::getCode)).toList();
    }

    public static boolean isRequester(CreditNoteRequest r) {
        return AppUserPrincipal.current().map(u -> u.getId().equals(r.getRequestedById())).orElse(false);
    }

    public static boolean isReviewer(CreditNoteRequest r) {
        return r.getReviewedById() != null && AppUserPrincipal.current().map(u -> u.getId().equals(r.getReviewedById())).orElse(false);
    }

    /** What a credit note would credit, checked as issuing checks it, with nothing changed (the request's totals). */
    Vat.Totals preview(CreditNoteDto form, CreditNoteKind kind) {
        SalesInvoice invoice = invoiceRepo.findDetailedById(form.getInvoiceId()).filter(i -> i.getStatus() == SalesInvoiceStatus.POSTED)
                .orElseThrow(() -> new NotFoundException("SalesInvoice", form.getInvoiceId()));
        reason(form);
        refundReason(form);
        Map<SalesInvoiceLine, Integer> pieces = new TreeMap<>(Comparator.comparingInt(SalesInvoiceLine::getLineNo));
        if (kind == CreditNoteKind.RETURN) {
            pickReturns(form, invoice).chosen().keySet().forEach(r -> pieces.merge(r.line(), 1, Integer::sum));
        } else {
            pickSizes(form, invoice).forEach((c, n) -> pieces.put(c.line(), n));
        }
        Vat.Totals totals = totals(creditLines(invoice, pieces));
        refund(form, invoice, totals, null);
        return totals;
    }

    /**
     * Asks for a credit note (F-14): checked as issuing would check it, its totals worked out, what it asks for kept; Finance
     * reviews it first when Settings ask, then another person approves it. A unit already asked back on a request still
     * open, or pieces of an invoice already being given up, cannot be asked twice.
     */
    @Transactional
    public CreditNoteRequest request(CreditNoteDto form, CreditNoteKind kind) {
        Vat.Totals totals = preview(form, kind);
        SalesInvoice invoice = invoiceRepo.findDetailedById(form.getInvoiceId()).orElseThrow();
        List<CreditNoteRequest> open = requestRepo.findByInvoice_IdAndStatusIn(invoice.getId(), List.of(CreditNoteRequestStatus.PENDING_REVIEW,
                CreditNoteRequestStatus.PENDING_APPROVAL, CreditNoteRequestStatus.APPROVED));
        CreditNoteRequest r = new CreditNoteRequest();
        if (kind == CreditNoteKind.RETURN) {
            for (CreditNoteDto.Item item : form.getItems()) {
                if (!item.isSelected()) {
                    continue;
                }
                for (CreditNoteRequest o : open) {
                    if (o.getItems().stream().anyMatch(i -> i.getUnitId().equals(item.getUnitId()))) {
                        String code = unitRepo.findById(item.getUnitId()).map(StockUnit::getCode).orElse("");
                        throw BusinessException.onField("items", "creditNote.request.unitAsked", code, o.getNumber());
                    }
                }
                r.getItems().add(new CreditNoteRequest.Item(item.getUnitId(), item.isCullet()));
            }
            r.setLocationId(form.getLocationId());
        } else {
            open.stream().filter(o -> o.getKind() == CreditNoteKind.CANCEL).findFirst().ifPresent(o -> {
                throw BusinessException.onField("sizes", "creditNote.request.cancelAsked", o.getNumber());
            });
            for (CreditNoteDto.Size size : form.getSizes()) {
                if (size.getQuantity() != null && size.getQuantity() > 0) {
                    r.getSizes().add(new CreditNoteRequest.Size(size.getLineId(), size.getQuantity()));
                }
            }
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        r.setNumber(numbers.next(DocumentType.CREDIT_NOTE_REQUEST));
        r.setKind(kind);
        r.setInvoice(invoice);
        r.setCustomer(invoice.getCustomer());
        r.setReason(reason(form));
        r.setRefundReason(refundReason(form));
        boolean refunded = CreditNotes.refund(totals.gross(), invoice.getBalanceDue()).refunded().signum() > 0;
        r.setRefundMethod(refunded ? form.getRefundMethod() : null);
        r.setRefundReference(refunded && form.getRefundMethod() != null && form.getRefundMethod().needsReference()
                ? PartyRules.clean(form.getRefundReference()) : null);
        r.setNetAmount(totals.net());
        r.setVatAmount(totals.vat());
        r.setTotalAmount(totals.gross());
        r.setAuthorityLevel(authority.required(AuthorityDecision.CREDIT_NOTE, totals.gross()));
        r.setStatus(reviewRequired() ? CreditNoteRequestStatus.PENDING_REVIEW : CreditNoteRequestStatus.PENDING_APPROVAL);
        r.setRequestedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        r.setRequestedById(user.map(AppUserPrincipal::getId).orElse(null));
        r.setRequestedAt(LocalDateTime.now(clock));
        requestRepo.save(r);
        tell(r);
        return r;
    }

    /** Finance reviews it (not who asked); then it waits for the approver. */
    @Transactional
    public CreditNoteRequest review(UUID id, String note) {
        CreditNoteRequest r = lockRequest(id);
        if (r.getStatus() != CreditNoteRequestStatus.PENDING_REVIEW) {
            throw BusinessException.of("creditNote.request.notForReview", r.getNumber());
        }
        if (isRequester(r)) {
            throw BusinessException.of("creditNote.request.ownReview", r.getNumber());
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        r.setReviewedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        r.setReviewedById(user.map(AppUserPrincipal::getId).orElse(null));
        r.setReviewedAt(LocalDateTime.now(clock));
        r.setReviewNote(PartyRules.clean(note));
        r.setStatus(CreditNoteRequestStatus.PENDING_APPROVAL);
        tell(r);
        return r;
    }

    /** Another person approves it within the Delegation of Authority (not who asked or reviewed it); then it can be issued. */
    @Transactional
    public CreditNoteRequest approve(UUID id, String note) {
        CreditNoteRequest r = lockRequest(id);
        if (r.getStatus() != CreditNoteRequestStatus.PENDING_APPROVAL) {
            throw BusinessException.of(r.getStatus() == CreditNoteRequestStatus.PENDING_REVIEW ? "creditNote.request.reviewFirst"
                    : "creditNote.request.notPending", r.getNumber());
        }
        if (isRequester(r)) {
            throw BusinessException.of("creditNote.request.ownApproval", r.getNumber());
        }
        if (isReviewer(r)) {
            throw BusinessException.of("creditNote.request.reviewerApproval", r.getNumber());
        }
        String clean = PartyRules.clean(note);
        authority.require(AuthorityDecision.CREDIT_NOTE, r.getTotalAmount(), clean);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        r.setDecidedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        r.setDecidedById(user.map(AppUserPrincipal::getId).orElse(null));
        r.setDecidedAt(LocalDateTime.now(clock));
        r.setDecisionNote(clean);
        r.setStatus(CreditNoteRequestStatus.APPROVED);
        notifier.user(r.getRequestedById(), NotificationKind.DECISION, "notify.creditNoteRequest.approved", "notify.decidedBy",
                "/credit-notes/requests/" + r.getId(), r.getNumber(), r.getDecidedBy());
        notifier.holders("ISSUE_CREDIT_NOTE", r.getDecidedById(), NotificationKind.APPROVAL, "notify.creditNoteRequest.toIssue",
                "notify.creditNoteRequest.toIssueText", "/credit-notes/requests/" + r.getId(), r.getNumber(), r.getCustomer().getName(),
                r.getTotalAmount().setScale(0, RoundingMode.HALF_UP).toPlainString());
        return r;
    }

    /** A reviewer or an approver rejects it (not who asked), with a reason. */
    @Transactional
    public CreditNoteRequest reject(UUID id, String reason) {
        CreditNoteRequest r = lockRequest(id);
        if (!r.isWaiting()) {
            throw BusinessException.of("creditNote.request.notPending", r.getNumber());
        }
        if (isRequester(r)) {
            throw BusinessException.of("creditNote.request.ownReject", r.getNumber());
        }
        closeRequest(r, CreditNoteRequestStatus.REJECTED, reason);
        notifier.user(r.getRequestedById(), NotificationKind.DECISION, "notify.creditNoteRequest.rejected", "notify.decidedBy",
                "/credit-notes/requests/" + r.getId(), r.getNumber(), r.getDecidedBy());
        return r;
    }

    /** Whoever asked takes it back (waiting, or approved and not issued yet), with a reason. */
    @Transactional
    public CreditNoteRequest withdraw(UUID id, String reason) {
        CreditNoteRequest r = lockRequest(id);
        if (!r.isWaiting() && r.getStatus() != CreditNoteRequestStatus.APPROVED) {
            throw BusinessException.of("creditNote.request.notPending", r.getNumber());
        }
        if (!isRequester(r)) {
            throw BusinessException.of("creditNote.request.notYours", r.getNumber());
        }
        closeRequest(r, CreditNoteRequestStatus.WITHDRAWN, reason);
        return r;
    }

    /**
     * Issues an approved request: the usual credit note (the glass comes back, the refund is paid from the issuer's till if in
     * cash), never for more than was approved. The request first, then the till and the invoice, as issuing takes them.
     */
    @Transactional
    public CreditNote issueApproved(UUID id) {
        CreditNoteRequest r = lockRequest(id);
        if (r.getStatus() != CreditNoteRequestStatus.APPROVED) {
            throw BusinessException.of("creditNote.request.notApproved", r.getNumber());
        }
        CreditNoteDto form = new CreditNoteDto();
        form.setRequestId(r.getId());
        form.setInvoiceId(r.getInvoice().getId());
        form.setReason(r.getReason());
        form.setRefundReason(r.getRefundReason());
        form.setRefundMethod(r.getRefundMethod());
        form.setRefundReference(r.getRefundReference());
        form.setLocationId(r.getLocationId());
        r.getItems().forEach(i -> {
            CreditNoteDto.Item item = new CreditNoteDto.Item();
            item.setUnitId(i.getUnitId());
            item.setSelected(true);
            item.setCullet(i.isCullet());
            form.getItems().add(item);
        });
        r.getSizes().forEach(sz -> {
            CreditNoteDto.Size size = new CreditNoteDto.Size();
            size.setLineId(sz.getLineId());
            size.setQuantity(sz.getQuantity());
            form.getSizes().add(size);
        });
        CreditNote note = r.getKind() == CreditNoteKind.RETURN ? issue(form) : cancelPieces(form);
        if (note.getTotalAmount().compareTo(r.getTotalAmount()) > 0) {
            throw BusinessException.of("creditNote.request.moreThanApproved", r.getNumber(), r.getTotalAmount(), note.getTotalAmount());
        }
        r.setCreditNoteId(note.getId());
        r.setIssuedBy(AppUserPrincipal.currentUsername());
        r.setIssuedAt(LocalDateTime.now(clock));
        r.setStatus(CreditNoteRequestStatus.ISSUED);
        return note;
    }

    private void closeRequest(CreditNoteRequest r, CreditNoteRequestStatus status, String reason) {
        String why = PartyRules.clean(reason);
        if (why == null || why.length() > 255) {
            throw BusinessException.of("po.reason.required");
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        r.setDecidedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        r.setDecidedById(user.map(AppUserPrincipal::getId).orElse(null));
        r.setDecidedAt(LocalDateTime.now(clock));
        r.setDecisionNote(why);
        r.setStatus(status);
    }

    /** The reviewers, or the approvers, are told it waits for them. */
    private void tell(CreditNoteRequest r) {
        boolean review = r.getStatus() == CreditNoteRequestStatus.PENDING_REVIEW;
        notifier.holders(review ? "REVIEW_CREDIT_CONTROL" : "APPROVE_CREDIT_NOTE", r.getRequestedById(), NotificationKind.APPROVAL,
                review ? "notify.creditNoteRequest.review" : "notify.creditNoteRequest.waiting", "notify.creditNoteRequest.waitingText",
                "/credit-notes/requests/" + r.getId(), r.getNumber(), r.getCustomer().getName(),
                r.getTotalAmount().setScale(0, RoundingMode.HALF_UP).toPlainString());
    }

    private CreditNoteRequest lockRequest(UUID id) {
        requestRepo.lockById(id).orElseThrow(() -> new NotFoundException("CreditNoteRequest", id));
        return findRequest(id);
    }

    // ---------------------------------------------------------------- the steps both take

    /** How the credit goes: the part off the balance due, the refund, its method and reference. */
    private record Refund(CreditNotes.Refund split, PaymentMethod method, String reference) {
    }

    /** A cash refund leaves the user's till: the till first, as every operation of the counter takes it. */
    private TillSession tillFor(CreditNoteDto form) {
        return form.getRefundMethod() == PaymentMethod.CASH ? tillService.lockCurrent() : null;
    }

    private SalesInvoice lockedInvoice(UUID id) {
        invoiceRepo.lockById(id).orElseThrow(() -> new NotFoundException("SalesInvoice", id));
        return invoiceRepo.findDetailedById(id).filter(i -> i.getStatus() == SalesInvoiceStatus.POSTED)
                .orElseThrow(() -> new NotFoundException("SalesInvoice", id));
    }

    /** The EBM refund reason chosen (spec 4.16). */
    private static String refundReason(CreditNoteDto form) {
        String code = form.getRefundReason() == null ? EbmCodes.DEFAULT_REFUND_REASON : form.getRefundReason().trim();
        if (!EbmCodes.REFUND_REASONS.contains(code)) {
            throw BusinessException.onField("refundReason", "creditNote.refundReason.invalid");
        }
        return code;
    }

    private static String reason(CreditNoteDto form) {
        String reason = PartyRules.clean(form.getReason());
        if (reason == null) {
            throw BusinessException.onField("reason", "creditNote.reason.required");
        }
        if (reason.length() > 255) {
            throw BusinessException.onField("reason", "creditNote.reason.size");
        }
        return reason;
    }

    /** What each line is credited for its pieces: its share, and its processing's, after what earlier credit notes took. */
    private List<CreditNoteLine> creditLines(SalesInvoice invoice, Map<SalesInvoiceLine, Integer> pieces) {
        Map<UUID, Integer> creditedBefore = creditedCounts(invoice);
        List<CreditNoteLine> lines = new ArrayList<>();
        for (Map.Entry<SalesInvoiceLine, Integer> e : pieces.entrySet()) {
            SalesInvoiceLine line = e.getKey();
            int before = creditedBefore.getOrDefault(line.getId(), 0);
            lines.add(creditLine(line, line.getQuantity(), before, e.getValue()));
            invoice.getLines().stream().filter(l -> l.getParentLine() == line)
                    .forEach(l -> lines.add(creditLine(l, line.getQuantity(), before, e.getValue())));
        }
        return lines;
    }

    private static Vat.Totals totals(List<CreditNoteLine> lines) {
        Vat.Totals totals = Vat.totals(lines.stream().map(l -> new Vat.Line(l.getTaxCode(), l.getVatRate(), l.getAmount())).toList());
        if (totals.gross().signum() <= 0) {
            throw BusinessException.onField("items", "creditNote.nothingToCredit");
        }
        return totals;
    }

    /** The credit reduces the balance due first; the rest is refunded the way chosen, if allowed. */
    private Refund refund(CreditNoteDto form, SalesInvoice invoice, Vat.Totals totals, TillSession till) {
        CreditNotes.Refund split = CreditNotes.refund(totals.gross(), invoice.getBalanceDue());
        if (split.refunded().signum() == 0) {
            return new Refund(split, null, null);
        }
        PaymentMethod method = form.getRefundMethod();
        if (method == null) {
            throw BusinessException.onField("refundMethod", "creditNote.refund.required");
        }
        String reference = PartyRules.clean(form.getRefundReference());
        if (method.needsReference() && reference == null) {
            throw BusinessException.onField("refundReference", "creditNote.refund.refRequired");
        }
        if (reference != null && reference.length() > 60) {
            throw BusinessException.onField("refundReference", "creditNote.refund.refSize");
        }
        if (method == PaymentMethod.CREDIT && !invoice.getCustomer().getType().isCreditAllowed()) {
            throw BusinessException.onField("refundMethod", "creditNote.refund.walkIn", invoice.getCustomer().getName());
        }
        if (method == PaymentMethod.CASH && till != null) {
            BigDecimal inTill = tillService.summary(till).getExpectedCash();
            if (inTill.compareTo(split.refunded()) < 0) {
                throw BusinessException.onField("refundMethod", "creditNote.refund.tillShort", till.getNumber(), inTill, split.refunded());
            }
        }
        return new Refund(split, method, method.needsReference() ? reference : null);
    }

    /** Saves the issued credit note and queues its EBM refund receipt (TAX-02), in the same transaction. */
    private CreditNote save(CreditNoteKind kind, SalesInvoice invoice, String reason, String refundReason, Vat.Totals totals,
                            Refund refund, TillSession till, UUID requestId) {
        LocalDateTime now = LocalDateTime.now(clock);
        CreditNote note = new CreditNote();
        note.setNumber(numbers.next(DocumentType.CREDIT_NOTE));
        note.setKind(kind);
        note.setInvoice(invoice);
        note.setCustomer(invoice.getCustomer());
        note.setCreditDate(now.toLocalDate());
        note.setReason(reason);
        note.setRefundReason(refundReason);
        note.setNetAmount(totals.net());
        note.setVatAmount(totals.vat());
        note.setTotalAmount(totals.gross());
        note.setBalanceReduced(refund.split().balanceReduced());
        note.setRefundMethod(refund.method());
        note.setRefundAmount(refund.split().refunded());
        note.setRefundReference(refund.reference());
        note.setTillSessionId(refund.method() == PaymentMethod.CASH ? till.getId() : null);
        note.setPostedAt(now);
        note.setPostedBy(AppUserPrincipal.currentUsername());
        note.setRequestId(requestId);
        CreditNote saved = repo.save(note);
        ebmService.queueRefund(saved);
        return saved;
    }

    private void saveUnit(CreditNote note, SalesInvoice invoice, SalesInvoiceLine line, StockUnit unit, ReturnOutcome outcome,
                          Location location) {
        CreditNoteUnit back = new CreditNoteUnit();
        back.setCreditNoteId(note.getId());
        back.setInvoiceId(invoice.getId());
        back.setInvoiceLineId(line.getId());
        back.setStockUnitId(unit.getId());
        back.setUnitCode(unit.getCode());
        back.setOutcome(outcome);
        back.setLocation(location);
        back.setUnitCost(unit.getUnitCost());
        returnedRepo.save(back);
    }

    /** The credit lines, numbered, and the balance due reduced. */
    private void finish(CreditNote note, SalesInvoice invoice, List<CreditNoteLine> lines, Refund refund) {
        int no = 1;
        for (CreditNoteLine line : lines) {
            line.setCreditNoteId(note.getId());
            line.setLineNo(no++);
            lineRepo.save(line);
        }
        if (refund.split().balanceReduced().signum() > 0) {
            invoice.setBalanceDue(invoice.getBalanceDue().subtract(refund.split().balanceReduced()));
        }
    }

    private static CreditNoteLine creditLine(SalesInvoiceLine line, int quantity, int before, int now) {
        CreditNoteLine credit = new CreditNoteLine();
        credit.setInvoiceLineId(line.getId());
        credit.setQuantity(now);
        credit.setTaxCode(line.getTaxCode());
        credit.setVatRate(line.getVatRate());
        credit.setAmount(CreditNotes.share(line.getAmount(), quantity, before, now));
        return credit;
    }

    /** Glass back in stock fits its rack's piece and weight limits (MD-03); a full sheet never goes on an off-cut rack. */
    private void checkRack(Location place, List<StockUnit> units, Map<UUID, Location> byId) {
        Location rack = StockService.rackOf(place, byId);
        if (rack.isOffcut()) {
            units.stream().filter(u -> u.getKind() == UnitKind.SHEET).findFirst().ifPresent(u -> {
                throw BusinessException.onField("locationId", "creditNote.sheetToOffcut", u.getCode(), rack.getCode());
            });
        }
        RackLoad now = stockService.rackLoads(byId).getOrDefault(rack.getId(), RackLoad.EMPTY);
        BigDecimal kg = units.stream().map(StockUnit::getWeightKg).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        RackLoad after = now.plus(units.size(), kg);
        if (after.exceedsPieces(rack.getMaxPieces())) {
            throw BusinessException.onField("locationId", "receipt.rack.pieces", rack.getCode(), now.pieces(), rack.getMaxPieces(), units.size());
        }
        if (after.exceedsKg(rack.getMaxWeightKg())) {
            throw BusinessException.onField("locationId", "receipt.rack.weight", rack.getCode(), now.kg(), rack.getMaxWeightKg(), kg);
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
