package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ExceptionsReportService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The month's exceptions for the Management Committee (F-03, F-14, Board Paper Table 9): every counter
 *               exception decided (price, below cost, credit, longer terms), every stock adjustment, manual journal and
 *               vehicle cost another person decided, every credit note asked and decided (or issued at once), every
 *               receivable write-off, purchase order approved and supplier payment authorised or rejected, with who asked, who
 *               decided, the office it needed and the note. Reads the documents only.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ExceptionsReportService {

    /** What an exception is about (its text: exceptions.kind.*). */
    public enum Kind { SALE_PRICE, SALE_BELOW_COST, SALE_CREDIT, SALE_TERMS, ADJUSTMENT, MANUAL_JOURNAL, VEHICLE_COST, CREDIT_NOTE,
        WRITE_OFF, PURCHASE_ORDER, SUPPLIER_PAYMENT }

    /** How it ended (exceptions.outcome.*). */
    public enum Outcome { APPROVED, REJECTED, ISSUED }

    /**
     * One exception: what, its number and link, when it was decided, what it was about, its amount (RWF, or % off for a
     * discount), the office it needed (when known), who asked, who decided, how it ended and the note.
     */
    public record Row(Kind kind, String number, String link, LocalDateTime at, String subject, BigDecimal amount, boolean percent,
                      AuthorityLevel office, String requestedBy, String decidedBy, Outcome outcome, String note) {
    }

    /** The month's rows in the order they were decided, and how many of each kind. */
    public record Report(YearMonth month, List<Row> rows, Map<Kind, Long> counts) {
    }

    private final SaleApprovalRepository saleApprovalRepo;
    private final StockAdjustmentRepository adjustmentRepo;
    private final ManualJournalRepository manualJournalRepo;
    private final VehicleCostRepository vehicleCostRepo;
    private final CreditNoteRepository creditNoteRepo;
    private final Clock clock;
    private final CreditNoteRequestRepository creditNoteRequestRepo;
    private final ReceivableWriteOffRepository writeOffRepo;
    private final PurchaseOrderRepository purchaseOrderRepo;
    private final SupplierPaymentRequestRepository paymentRequestRepo;

    public ExceptionsReportService(SaleApprovalRepository saleApprovalRepo, StockAdjustmentRepository adjustmentRepo,
                                   ManualJournalRepository manualJournalRepo, VehicleCostRepository vehicleCostRepo,
                                   CreditNoteRepository creditNoteRepo, Clock clock, CreditNoteRequestRepository creditNoteRequestRepo,
                                   ReceivableWriteOffRepository writeOffRepo, PurchaseOrderRepository purchaseOrderRepo,
                                   SupplierPaymentRequestRepository paymentRequestRepo) {
        this.creditNoteRequestRepo = creditNoteRequestRepo;
        this.writeOffRepo = writeOffRepo;
        this.purchaseOrderRepo = purchaseOrderRepo;
        this.paymentRequestRepo = paymentRequestRepo;
        this.saleApprovalRepo = saleApprovalRepo;
        this.adjustmentRepo = adjustmentRepo;
        this.manualJournalRepo = manualJournalRepo;
        this.vehicleCostRepo = vehicleCostRepo;
        this.creditNoteRepo = creditNoteRepo;
        this.clock = clock;
    }

    public YearMonth thisMonth() {
        return YearMonth.now(clock);
    }

    public Report of(YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.atEndOfMonth().plusDays(1).atStartOfDay().minusNanos(1);
        List<Row> rows = new ArrayList<>();
        for (SaleApproval a : saleApprovalRepo.findByDecidedAtBetweenAndStatusInOrderByDecidedAtAsc(from, to,
                List.of(SaleApprovalStatus.APPROVED, SaleApprovalStatus.REJECTED))) {
            Kind kind = a.isPrice() ? (a.isBelowCost() ? Kind.SALE_BELOW_COST : Kind.SALE_PRICE) : a.isTerms() ? Kind.SALE_TERMS : Kind.SALE_CREDIT;
            BigDecimal amount = a.isPrice() ? (a.isBelowCost() ? a.getAmountAfter() : a.getDiscountPercent())
                    : a.isTerms() ? a.getAmountAfter() : a.getCreditAmount();
            rows.add(new Row(kind, a.getNumber(), "/sale-approvals/" + a.getId(), a.getDecidedAt(), a.getSubject() + " · " + a.getReason(),
                    amount, a.isPrice() && !a.isBelowCost(), a.getAuthorityLevel(), a.getRequestedBy(), a.getDecidedBy(),
                    a.isApproved() ? Outcome.APPROVED : Outcome.REJECTED, a.getDecisionNote()));
        }
        for (StockAdjustment a : adjustmentRepo.findByDecidedAtBetweenOrderByDecidedAtAsc(from, to)) {
            if (a.getStatus() != AdjustmentStatus.POSTED && a.getStatus() != AdjustmentStatus.REJECTED) {
                continue;
            }
            rows.add(new Row(Kind.ADJUSTMENT, a.getNumber(), "/stock-adjustments/" + a.getId(), a.getDecidedAt(), a.getReason(),
                    a.getValueMoved(), false, null, a.getRequestedBy(), a.getDecidedBy(),
                    a.getStatus() == AdjustmentStatus.POSTED ? Outcome.APPROVED : Outcome.REJECTED, a.getDecisionNote()));
        }
        for (ManualJournal j : manualJournalRepo.findByDecidedAtBetweenOrderByDecidedAtAsc(from, to)) {
            boolean approved = j.getStatus() == ManualJournalStatus.POSTED || j.getStatus() == ManualJournalStatus.REVERSED;
            if (!approved && j.getStatus() != ManualJournalStatus.REJECTED) {
                continue;
            }
            rows.add(new Row(Kind.MANUAL_JOURNAL, j.getNumber(), "/accounting/manual-journals/" + j.getId(), j.getDecidedAt(), j.getDescription(),
                    j.getTotal(), false, null, j.getRequestedBy(), j.getDecidedBy(), approved ? Outcome.APPROVED : Outcome.REJECTED,
                    j.getDecisionNote()));
        }
        for (VehicleCost c : vehicleCostRepo.findByDecidedAtBetweenOrderByDecidedAtAsc(from, to)) {
            boolean approved = c.getStatus() == VehicleCostStatus.POSTED || c.getStatus() == VehicleCostStatus.REVERSED;
            if (!approved && c.getStatus() != VehicleCostStatus.REJECTED) {
                continue;
            }
            rows.add(new Row(Kind.VEHICLE_COST, c.getNumber(), "/vehicle-costs/" + c.getId(), c.getDecidedAt(),
                    c.getVehicle().getPlate() + " · " + c.getDescription(), c.getAmount(), false, null, c.getRequestedBy(), c.getDecidedBy(),
                    approved ? Outcome.APPROVED : Outcome.REJECTED, c.getDecisionNote()));
        }
        for (CreditNoteRequest r : creditNoteRequestRepo.findByDecidedAtBetweenOrderByDecidedAtAsc(from, to)) {
            if (r.getStatus() == CreditNoteRequestStatus.WITHDRAWN) {
                continue;
            }
            rows.add(new Row(Kind.CREDIT_NOTE, r.getNumber(), "/credit-notes/requests/" + r.getId(), r.getDecidedAt(),
                    r.getInvoice().getNumber() + " · " + r.getCustomer().getName() + " · " + r.getReason(), r.getTotalAmount(), false,
                    r.getAuthorityLevel(), r.getRequestedBy(), r.getDecidedBy(),
                    r.getStatus() == CreditNoteRequestStatus.REJECTED ? Outcome.REJECTED : Outcome.APPROVED, r.getDecisionNote()));
        }
        for (ReceivableWriteOff w : writeOffRepo.findByDecidedAtBetweenOrderByDecidedAtAsc(from, to)) {
            if (w.getStatus() == WriteOffStatus.WITHDRAWN) {
                continue;
            }
            rows.add(new Row(Kind.WRITE_OFF, w.getNumber(), "/accounting/write-offs/" + w.getId(), w.getDecidedAt(),
                    w.getCustomer().getName() + " · " + w.getReason(), w.getAmount(), false, w.getAuthorityLevel(), w.getRequestedBy(),
                    w.getDecidedBy(), w.getStatus() == WriteOffStatus.POSTED ? Outcome.APPROVED : Outcome.REJECTED, w.getDecisionNote()));
        }
        for (PurchaseOrder o : purchaseOrderRepo.findByApprovedAtBetweenOrderByApprovedAtAsc(from, to)) {
            rows.add(new Row(Kind.PURCHASE_ORDER, o.getNumber(), "/purchase-orders/" + o.getId(), o.getApprovedAt(), o.getSupplier().getName(),
                    o.getApprovalAmount(), false, o.getAuthorityLevel(), o.getSubmittedBy(), o.getApprovedBy(), Outcome.APPROVED, o.getApprovalNote()));
        }
        for (SupplierPaymentRequest r : paymentRequestRepo.findByDecidedAtBetweenOrderByDecidedAtAsc(from, to)) {
            if (r.getStatus() == PaymentRequestStatus.CANCELLED) {
                continue;
            }
            String by = r.getSecondBy() == null ? r.getDecidedBy() : r.getFirstBy() + ", " + r.getSecondBy();
            rows.add(new Row(Kind.SUPPLIER_PAYMENT, r.getNumber(), "/supplier-payments/requests/" + r.getId(), r.getDecidedAt(),
                    r.getSupplier().getName() + " · " + r.getAmount().toPlainString() + " " + r.getCurrencyCode(), r.getBaseEstimate(), false,
                    r.getAuthorityLevel(), r.getPreparedBy(), r.getStatus() == PaymentRequestStatus.PAID ? by : r.getDecidedBy(),
                    r.getStatus() == PaymentRequestStatus.PAID ? Outcome.APPROVED : Outcome.REJECTED,
                    r.getStatus() == PaymentRequestStatus.PAID ? r.getSecondNote() != null ? r.getSecondNote() : r.getFirstNote() : r.getDecisionNote()));
        }
        for (CreditNote n : creditNoteRepo.findByPostedAtBetweenOrderByPostedAtAsc(from, to)) {
            if (n.getRequestId() != null) {
                continue;                                 // its request's row says who approved it
            }
            rows.add(new Row(Kind.CREDIT_NOTE, n.getNumber(), "/credit-notes/" + n.getId(), n.getPostedAt(),
                    n.getInvoice().getNumber() + " · " + n.getCustomer().getName() + " · " + n.getReason(), n.getTotalAmount(), false, null,
                    n.getPostedBy(), n.getPostedBy(), Outcome.ISSUED, null));
        }
        rows.sort(Comparator.comparing(Row::at).thenComparing(Row::number));
        Map<Kind, Long> counts = new EnumMap<>(Kind.class);
        rows.forEach(r -> counts.merge(r.kind(), 1L, Long::sum));
        return new Report(month, rows, counts);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
