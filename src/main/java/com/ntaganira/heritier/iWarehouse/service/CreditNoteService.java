package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.CreditNoteDto;
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
 * - User      : Hntaganira
 * - Desc      : Returns and credit notes (POS-09). The customer brings back units taken on an issued invoice: sheets sold
 *               from stock and pieces handed over, each once. Each goes back on a rack (available, at its own cost: the
 *               MAC moves) or to cullet (BROKEN). Each invoice line is credited its share of the pieces back
 *               (CreditNotes.share), VAT per tax letter. The credit first reduces the invoice's balance due; the rest is
 *               refunded in cash from the user's till (if it holds that much), by mobile money, card or transfer, or to
 *               the customer's account (not for walk-ins). Issuing locks the till (cash refunds), then the invoice, then
 *               the products, and posts the journal.
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
    private final TillService tillService;
    private final StockService stockService;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final Clock clock;

    public CreditNoteService(CreditNoteRepository repo, CreditNoteLineRepository lineRepo, CreditNoteUnitRepository returnedRepo,
                             SalesInvoiceRepository invoiceRepo, SalesDeliveryRepository deliveryRepo, StockUnitRepository unitRepo,
                             ProductRepository productRepo, TillService tillService, StockService stockService,
                             PostingService postingService, DocumentNumberService numbers, Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.returnedRepo = returnedRepo;
        this.invoiceRepo = invoiceRepo;
        this.deliveryRepo = deliveryRepo;
        this.unitRepo = unitRepo;
        this.productRepo = productRepo;
        this.tillService = tillService;
        this.stockService = stockService;
        this.postingService = postingService;
        this.numbers = numbers;
        this.clock = clock;
    }

    /**
     * A unit the customer took on an invoice and can bring back: its invoice line (the sheet's, or the size it was cut for),
     * the line's pieces and how many came back before, the amounts credited per piece (the line's and its processing's) and
     * about what one piece is worth.
     */
    public record Returnable(StockUnit unit, SalesInvoiceLine line, int quantity, int returned, List<BigDecimal> amounts) {

        /** One piece's share of the line and its processing, whole RWF. */
        public BigDecimal getValueEach() {
            return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(quantity), 0, RoundingMode.HALF_UP);
        }

        /** The amounts, for the form's running total: "13500.00,6000.00". */
        public String getAmountList() {
            return amounts.stream().map(BigDecimal::toPlainString).collect(Collectors.joining(","));
        }
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

    /** Pieces brought back per invoice line (the sheet's, or the size's). */
    public Map<UUID, Integer> returnedCounts(UUID invoiceId) {
        Map<UUID, Integer> counts = new HashMap<>();
        returnedRepo.findByInvoiceId(invoiceId).forEach(u -> counts.merge(u.getInvoiceLineId(), 1, Integer::sum));
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
        Map<UUID, Integer> returned = returnedCounts(invoice.getId());
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
            List<BigDecimal> amounts = new ArrayList<>();
            amounts.add(line.getAmount());
            invoice.getLines().stream().filter(l -> l.getParentLine() == line).forEach(l -> amounts.add(l.getAmount()));
            items.add(new Returnable(unit, line, line.getQuantity(), returned.getOrDefault(line.getId(), 0), amounts));
        }
        items.sort(Comparator.comparing((Returnable r) -> r.line().getLineNo()).thenComparing(r -> r.unit().getCode()));
        return items;
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

    // ---------------------------------------------------------------- issuing (POS-09)

    /**
     * Issues the credit note of a return: checks what comes back and where it goes, credits the lines, puts the units back
     * on the rack or to cullet (moving each glass's MAC with what came back), reduces the invoice's balance due, records
     * the refund and posts the journal.
     */
    @Transactional
    public CreditNote issue(CreditNoteDto form) {
        // A cash refund leaves the user's till: the till first, as every operation of the counter takes it
        TillSession till = form.getRefundMethod() == PaymentMethod.CASH ? tillService.lockCurrent() : null;
        invoiceRepo.lockById(form.getInvoiceId()).orElseThrow(() -> new NotFoundException("SalesInvoice", form.getInvoiceId()));
        SalesInvoice invoice = invoiceRepo.findDetailedById(form.getInvoiceId())
                .filter(i -> i.getStatus() == SalesInvoiceStatus.POSTED)
                .orElseThrow(() -> new NotFoundException("SalesInvoice", form.getInvoiceId()));
        String reason = PartyRules.clean(form.getReason());
        if (reason == null) {
            throw BusinessException.onField("reason", "creditNote.reason.required");
        }
        if (reason.length() > 255) {
            throw BusinessException.onField("reason", "creditNote.reason.size");
        }

        // What comes back, and where it goes
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

        // What each line is credited: its share of the pieces back, and its processing's
        Map<SalesInvoiceLine, Integer> pieces = new TreeMap<>(Comparator.comparingInt(SalesInvoiceLine::getLineNo));
        chosen.keySet().forEach(r -> pieces.merge(r.line(), 1, Integer::sum));
        Map<UUID, Integer> returnedBefore = returnedCounts(invoice.getId());
        List<CreditNoteLine> lines = new ArrayList<>();
        for (Map.Entry<SalesInvoiceLine, Integer> e : pieces.entrySet()) {
            SalesInvoiceLine line = e.getKey();
            int before = returnedBefore.getOrDefault(line.getId(), 0);
            lines.add(creditLine(line, line.getQuantity(), before, e.getValue()));
            invoice.getLines().stream().filter(l -> l.getParentLine() == line)
                    .forEach(l -> lines.add(creditLine(l, line.getQuantity(), before, e.getValue())));
        }
        Vat.Totals totals = Vat.totals(lines.stream().map(l -> new Vat.Line(l.getTaxCode(), l.getVatRate(), l.getAmount())).toList());
        if (totals.gross().signum() <= 0) {
            throw BusinessException.onField("items", "creditNote.nothingToCredit");
        }

        // The credit reduces the balance due first; the rest is refunded
        CreditNotes.Refund split = CreditNotes.refund(totals.gross(), invoice.getBalanceDue());
        PaymentMethod method = null;
        String reference = null;
        if (split.refunded().signum() > 0) {
            method = form.getRefundMethod();
            if (method == null) {
                throw BusinessException.onField("refundMethod", "creditNote.refund.required");
            }
            reference = PartyRules.clean(form.getRefundReference());
            if (method.needsReference() && reference == null) {
                throw BusinessException.onField("refundReference", "creditNote.refund.refRequired");
            }
            if (reference != null && reference.length() > 60) {
                throw BusinessException.onField("refundReference", "creditNote.refund.refSize");
            }
            if (!method.needsReference()) {
                reference = null;
            }
            if (method == PaymentMethod.CREDIT && !invoice.getCustomer().getType().isCreditAllowed()) {
                throw BusinessException.onField("refundMethod", "creditNote.refund.walkIn", invoice.getCustomer().getName());
            }
            if (method == PaymentMethod.CASH) {
                BigDecimal inTill = tillService.summary(till).getExpectedCash();
                if (inTill.compareTo(split.refunded()) < 0) {
                    throw BusinessException.onField("refundMethod", "creditNote.refund.tillShort", till.getNumber(), inTill, split.refunded());
                }
            }
        }

        // The glass: products locked, their value read before anything moves
        List<StockUnit> units = chosen.keySet().stream().map(Returnable::unit).toList();
        Map<UUID, Product> products = productRepo.lockAllById(units.stream().map(u -> u.getProduct().getId())
                        .collect(Collectors.toCollection(TreeSet::new))).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<UUID, BigDecimal> heldBefore = new HashMap<>();
        products.keySet().forEach(id -> heldBefore.put(id, stockService.heldArea(id)));
        PostingService.StockValues before = postingService.stockValues(products.values());

        LocalDateTime now = LocalDateTime.now(clock);
        CreditNote note = new CreditNote();
        note.setNumber(numbers.next(DocumentType.CREDIT_NOTE));
        note.setInvoice(invoice);
        note.setCustomer(invoice.getCustomer());
        note.setCreditDate(now.toLocalDate());
        note.setReason(reason);
        note.setNetAmount(totals.net());
        note.setVatAmount(totals.vat());
        note.setTotalAmount(totals.gross());
        note.setBalanceReduced(split.balanceReduced());
        note.setRefundMethod(method);
        note.setRefundAmount(split.refunded());
        note.setRefundReference(reference);
        note.setTillSessionId(method == PaymentMethod.CASH ? till.getId() : null);
        note.setPostedAt(now);
        note.setPostedBy(AppUserPrincipal.currentUsername());
        repo.save(note);

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
            CreditNoteUnit back = new CreditNoteUnit();
            back.setCreditNoteId(note.getId());
            back.setInvoiceId(invoice.getId());
            back.setInvoiceLineId(e.getKey().line().getId());
            back.setStockUnitId(unit.getId());
            back.setUnitCode(unit.getCode());
            back.setOutcome(e.getValue());
            back.setLocation(e.getValue() == ReturnOutcome.RESTOCK ? location : null);
            back.setUnitCost(unit.getUnitCost());
            returnedRepo.save(back);
        }
        // Glass back on the racks at its own cost moves the MAC (as a unit found again does)
        for (Map.Entry<UUID, BigDecimal> e : areaBack.entrySet()) {
            Product product = products.get(e.getKey());
            product.setMacPerM2(Costing.afterStockChange(heldBefore.get(e.getKey()), product.getMacPerM2(), e.getValue(),
                    valueBack.get(e.getKey())));
        }
        int no = 1;
        for (CreditNoteLine line : lines) {
            line.setCreditNoteId(note.getId());
            line.setLineNo(no++);
            lineRepo.save(line);
        }
        if (split.balanceReduced().signum() > 0) {
            invoice.setBalanceDue(invoice.getBalanceDue().subtract(split.balanceReduced()));
        }
        postingService.creditNote(note, restockedCost, culletCost, before);
        return note;
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
