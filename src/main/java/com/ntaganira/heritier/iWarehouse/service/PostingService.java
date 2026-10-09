package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.AdjustmentKind;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.WriteOffCause;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.CurrencyRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PostingService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : The posting matrix (SRS 4.9.1, ACC-04): one rule per business event, called inside the event's
 *               transaction once its stock and costs have changed. Stock lines are the change of each glass's
 *               value (m² held x MAC, rounded per glass, as the stock valuation computes it), so the inventory
 *               account always equals the valuation (AT-10); the event's own amounts (what the supplier will
 *               invoice, what was expensed) go to the other accounts, and the moving-average rounding between
 *               the two to Inventory Revaluation. The ledger starts with the opening stock journal.
 * </pre>
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PostingService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(Journal.SCALE);

    private final JournalService journalService;
    private final StockService stockService;
    private final ProductRepository productRepo;
    private final CurrencyRepository currencyRepo;
    private final Clock clock;

    public PostingService(JournalService journalService, StockService stockService, ProductRepository productRepo,
                          CurrencyRepository currencyRepo, Clock clock) {
        this.journalService = journalService;
        this.stockService = stockService;
        this.productRepo = productRepo;
        this.currencyRepo = currencyRepo;
        this.clock = clock;
    }

    /** The stock value of some glass at one moment: the products (locked by the caller) and their values. */
    public record StockValues(Map<UUID, Product> products, Map<UUID, BigDecimal> values) {
    }

    /**
     * The stock value of these glasses now. Called after the event has locked them and before it changes the
     * stock: the rule compares it with the value once the stock and the MAC have changed.
     */
    public StockValues stockValues(Collection<Product> products) {
        Map<UUID, Product> byId = new LinkedHashMap<>();
        products.forEach(p -> byId.put(p.getId(), p));
        return new StockValues(byId, valuesOf(byId.values()));
    }

    private Map<UUID, BigDecimal> valuesOf(Collection<Product> products) {
        Map<UUID, BigDecimal> values = new LinkedHashMap<>();
        for (Product p : products) {
            BigDecimal mac = p.getMacPerM2();
            values.put(p.getId(), mac == null ? ZERO
                    : stockService.heldArea(p.getId()).multiply(mac).setScale(Journal.SCALE, RoundingMode.HALF_UP));
        }
        return values;
    }

    private Map<UUID, BigDecimal> valuesNow(StockValues before) {
        return valuesOf(before.products().values());
    }

    // ---------------------------------------------------------------- purchasing (PRC-02, PRC-05, PRC-06)

    /**
     * Crate received: Dr Inventory (the glass that came in) / Cr Goods Received Not Invoiced, at the order's price
     * and the receipt's rate for every sheet the supplier will invoice. Sheets broken on arrival are expensed
     * to spoilage; a claim brings back what it recovers.
     */
    public JournalEntry goodsReceipt(GoodsReceipt receipt, StockValues before) {
        PurchaseOrder order = receipt.getPurchaseOrder();
        BigDecimal invoiced = BigDecimal.ZERO;
        BigDecimal broken = BigDecimal.ZERO;
        BigDecimal foreign = BigDecimal.ZERO;
        for (CrateBatch crate : receipt.getCrates()) {
            BigDecimal unitCost = Costing.unitCost(crate.getSheetArea(), crate.getCostPerM2());
            BigDecimal sheets = BigDecimal.valueOf(crate.getSheets() + crate.getBroken());
            invoiced = invoiced.add(unitCost.multiply(sheets));
            broken = broken.add(unitCost.multiply(BigDecimal.valueOf(crate.getBroken())));
            foreign = foreign.add(crate.getPoLine().getPricePerM2().multiply(crate.getSheetArea()).multiply(sheets));
        }
        Supplier supplier = order.getSupplier();
        Journal journal = Journal.of(JournalSource.GOODS_RECEIPT, receipt.getId(), receipt.getNumber(), receipt.getReceivedDate(),
                        "Goods receipt " + receipt.getNumber() + " from " + supplier.getName())
                .stockChange(before.values(), valuesNow(before))
                .debit(AccountKey.SPOILAGE, broken)
                .add(AccountKey.GRNI, invoiced.negate(), null, supplier.getId(), receipt.getNumber(),
                        fx(order.getCurrencyCode(), foreign, receipt.getRate()))
                .balanceOn(AccountKey.STOCK_REVALUATION);
        return journalService.post(journal);
    }

    /**
     * Import bills posted on a shipment (PRC-05): Dr Inventory (the part that reached glass in stock), Cost of
     * Goods Sold (the part of glass already gone), Glass Spoilage (the part of sheets broken on arrival) / Cr
     * Accounts Payable for a bill with a supplier, Accrued Import Charges for one without. A credit note (a
     * negative bill) posts the other way. {@code base} are the bills' unrounded RWF amounts, {@code total}
     * their sum rounded once.
     */
    public JournalEntry shipmentPosting(Shipment shipment, int postingNo, List<ShipmentCost> bills, List<BigDecimal> base,
                                        BigDecimal total, BigDecimal expensed, BigDecimal broken, StockValues before) {
        Journal journal = Journal.of(JournalSource.SHIPMENT, shipment.getId(), shipment.getNumber(), today(),
                        "Import costs of shipment " + shipment.getNumber() + ", posting " + postingNo)
                .stockChange(before.values(), valuesNow(before))
                .debit(AccountKey.COGS, expensed)
                .debit(AccountKey.SPOILAGE, broken);
        List<BigDecimal> amounts = Journal.roundTo(total, base);
        for (int i = 0; i < bills.size(); i++) {
            ShipmentCost bill = bills.get(i);
            Supplier supplier = bill.getSupplier();
            journal.add(supplier != null ? AccountKey.PAYABLE : AccountKey.IMPORT_ACCRUAL, amounts.get(i).negate(), null,
                    supplier == null ? null : supplier.getId(), memo(bill), fx(bill.getCurrencyCode(), bill.getAmount(), bill.getRate()));
        }
        return journalService.post(journal.balanceOn(AccountKey.STOCK_REVALUATION));
    }

    /** A claim sent for sheets broken on arrival (PRC-06): Dr Claims Receivable / Cr Glass Spoilage, the amount claimed. */
    public JournalEntry claimOpened(Shipment shipment) {
        Journal journal = Journal.of(JournalSource.CLAIM_OPENED, shipment.getId(), shipment.getNumber(), shipment.getClaimDate(),
                        "Claim on shipment " + shipment.getNumber() + " to " + shipment.getClaimParty())
                .debit(AccountKey.CLAIMS, shipment.getClaimAmount())
                .credit(AccountKey.SPOILAGE, shipment.getClaimAmount());
        return journalService.post(journal);
    }

    /**
     * A claim settled: Dr the account the money came into (bank, cash, mobile money, or the supplier's credit on
     * Accounts Payable), Dr Glass Spoilage for the shortfall (Cr when more came back than claimed) / Cr Claims
     * Receivable, the amount claimed. Nothing is posted for a claim opened before the ledger started.
     */
    public JournalEntry claimSettled(Shipment shipment) {
        if (!journalService.hasJournal(JournalSource.CLAIM_OPENED, shipment.getId())) {
            return null;
        }
        BigDecimal claimed = shipment.getClaimAmount();
        BigDecimal received = shipment.getClaimSettledAmount();
        Journal journal = Journal.of(JournalSource.CLAIM_SETTLED, shipment.getId(), shipment.getNumber(), today(),
                "Claim on shipment " + shipment.getNumber() + " settled");
        if (received.signum() > 0) {
            journal.debit(shipment.getClaimReceivedInto().account(), received);
        }
        journal.debit(AccountKey.SPOILAGE, claimed.subtract(received))
                .credit(AccountKey.CLAIMS, claimed);
        return journalService.post(journal);
    }

    /** A claim rejected: Dr Glass Spoilage / Cr Claims Receivable, the amount claimed. */
    public JournalEntry claimRejected(Shipment shipment) {
        if (!journalService.hasJournal(JournalSource.CLAIM_OPENED, shipment.getId())) {
            return null;
        }
        Journal journal = Journal.of(JournalSource.CLAIM_REJECTED, shipment.getId(), shipment.getNumber(), today(),
                        "Claim on shipment " + shipment.getNumber() + " rejected")
                .debit(AccountKey.SPOILAGE, shipment.getClaimAmount())
                .credit(AccountKey.CLAIMS, shipment.getClaimAmount());
        return journalService.post(journal);
    }

    // ---------------------------------------------------------------- production (PRD-05, PRD-07)

    /** A cut recorded: Dr Glass Spoilage (cullet and breakage at the sheet's cost) / Cr Inventory. */
    public JournalEntry cut(CuttingJob job, BigDecimal spoilage, StockValues before) {
        Journal journal = Journal.of(JournalSource.CUTTING_JOB, job.getId(), job.getNumber(), today(),
                        "Cutting job " + job.getNumber())
                .debit(AccountKey.SPOILAGE, spoilage)
                .stockChange(before.values(), valuesNow(before))
                .balanceOn(AccountKey.STOCK_REVALUATION);
        return journalService.post(journal);
    }

    // ---------------------------------------------------------------- stock operations (INV-07)

    /**
     * A stock adjustment posted: glass written off as damaged to Glass Spoilage; missing glass, glass found
     * again, units added and sizes corrected to Inventory Adjustment Expense (a gain credits it) / Inventory.
     */
    public JournalEntry adjustment(StockAdjustment adjustment, StockValues before) {
        Journal journal = Journal.of(JournalSource.ADJUSTMENT, adjustment.getId(), adjustment.getNumber(), today(),
                "Stock adjustment " + adjustment.getNumber());
        for (StockAdjustmentLine line : adjustment.getLines()) {
            boolean damaged = line.getKind() == AdjustmentKind.WRITE_OFF && line.getCause() == WriteOffCause.DAMAGED;
            journal.debit(damaged ? AccountKey.SPOILAGE : AccountKey.STOCK_ADJUSTMENT, line.getValueChange().negate());
        }
        journal.stockChange(before.values(), valuesNow(before))
                .balanceOn(AccountKey.STOCK_REVALUATION);
        return journalService.post(journal);
    }

    // ---------------------------------------------------------------- counter sales (POS-01, POS-04, POS-10)

    /**
     * A sale paid (SRS 4.9.1): Dr each payment's account (cash in the till, mobile money, bank for card and
     * transfer, the customer's receivable for credit) / Cr Sales Revenue (net) and VAT Output; and the glass at
     * MAC: Dr Cost of Goods Sold / Cr Inventory, the change of each glass's value.
     */
    public JournalEntry sale(SalesInvoice invoice, List<SalesPayment> payments, StockValues before) {
        Customer customer = invoice.getCustomer();
        Journal journal = Journal.of(JournalSource.SALES_INVOICE, invoice.getId(), invoice.getNumber(), invoice.getInvoiceDate(),
                "Sale " + invoice.getNumber() + " to " + invoice.getBillTo());
        for (SalesPayment p : payments) {
            UUID customerId = p.getMethod() == PaymentMethod.CREDIT ? customer.getId() : null;
            journal.add(p.getMethod().account(), p.getAmount(), null, null, customerId, p.getReference(), null);
        }
        journal.credit(AccountKey.SALES, invoice.getNetAmount())
                .credit(AccountKey.VAT_OUTPUT, invoice.getVatAmount());
        Map<UUID, BigDecimal> after = valuesNow(before);
        journal.stockChange(before.values(), after);
        BigDecimal cost = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal> e : before.values().entrySet()) {
            cost = cost.add(e.getValue().subtract(after.getOrDefault(e.getKey(), BigDecimal.ZERO)));
        }
        journal.debit(AccountKey.COGS, cost);
        return journalService.post(journal);
    }

    /**
     * Pieces of a paid sale's custom sizes handed over (SRS 5.3 step 5): Dr Cost of Goods Sold / Cr Inventory, the
     * change of each glass's value (the sale itself was posted when it was paid).
     */
    public JournalEntry saleDelivery(SalesInvoice invoice, StockValues before) {
        Journal journal = Journal.of(JournalSource.SALES_DELIVERY, invoice.getId(), invoice.getNumber(), today(),
                "Pieces of sale " + invoice.getNumber() + " handed over");
        Map<UUID, BigDecimal> after = valuesNow(before);
        journal.stockChange(before.values(), after);
        BigDecimal cost = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal> e : before.values().entrySet()) {
            cost = cost.add(e.getValue().subtract(after.getOrDefault(e.getKey(), BigDecimal.ZERO)));
        }
        journal.debit(AccountKey.COGS, cost);
        return journalService.post(journal);
    }

    /** A till opened: its float leaves the main cash vault for the till (Dr Cash on Hand / Cr Main Cash Vault). */
    public JournalEntry tillOpened(TillSession session) {
        Journal journal = Journal.of(JournalSource.TILL_OPENED, session.getId(), session.getNumber(),
                        session.getOpenedAt().toLocalDate(), "Till " + session.getNumber() + " opened by " + session.getCashierUsername())
                .debit(AccountKey.CASH, session.getOpeningFloat())
                .credit(AccountKey.CASH_VAULT, session.getOpeningFloat());
        return journalService.post(journal);
    }

    /**
     * A till closed: the cash counted goes back to the vault (Dr Main Cash Vault / Cr Cash on Hand, the cash
     * expected); a shortage is Dr Cash Over/Short, an overage Cr (ACC-07 for the counter).
     */
    public JournalEntry tillClosed(TillSession session) {
        Journal journal = Journal.of(JournalSource.TILL_CLOSED, session.getId(), session.getNumber(),
                        session.getClosedAt().toLocalDate(), "Till " + session.getNumber() + " closed")
                .debit(AccountKey.CASH_VAULT, session.getCountedCash())
                .credit(AccountKey.CASH, session.getExpectedCash())
                .balanceOn(AccountKey.CASH_OVER_SHORT);
        return journalService.post(journal);
    }

    // ---------------------------------------------------------------- opening

    /**
     * Starts the ledger: brings the inventory account of each glass to its stock value at this moment (Dr
     * Inventory / Cr Opening Balance Equity). All products are locked, so no stock event runs meanwhile.
     * Once only; events posted before it are taken into account.
     */
    @Transactional
    public JournalEntry openingStock() {
        Optional<JournalEntry> existing = journalService.openingStock();
        if (existing.isPresent()) {
            throw BusinessException.of("journal.opening.exists", existing.get().getNumber());
        }
        List<Product> products = productRepo.lockAllById(productRepo.findAll().stream().map(Product::getId).toList());
        Map<UUID, BigDecimal> values = valuesOf(products);
        Map<UUID, BigDecimal> ledger = journalService.inventoryByProduct();
        Journal journal = Journal.of(JournalSource.OPENING_STOCK, null, null, today(), "Opening stock at moving average cost")
                .stockChange(ledger, values)
                .balanceOn(AccountKey.OPENING_EQUITY);
        if (journal.isEmpty()) {
            throw BusinessException.of("journal.opening.nothing");
        }
        return journalService.post(journal);
    }

    // ---------------------------------------------------------------- helpers

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The foreign amount of a line in another currency than RWF (ACC-01); null in RWF. */
    private Journal.Fx fx(String currencyCode, BigDecimal amount, BigDecimal rate) {
        String base = currencyRepo.findByBaseCurrencyTrue().map(Currency::getCode).orElse(null);
        if (currencyCode == null || currencyCode.equals(base) || rate == null) {
            return null;
        }
        return new Journal.Fx(currencyCode, amount.setScale(Journal.SCALE, RoundingMode.HALF_UP), rate);
    }

    /** "INV-778 · Freight Shanghai-Mombasa": what identifies a bill on its line. */
    private static String memo(ShipmentCost bill) {
        String memo = Stream.of(bill.getInvoiceRef(), bill.getDescription())
                .filter(StringUtils::hasText).map(String::trim).collect(Collectors.joining(" · "));
        return memo.isEmpty() ? null : memo.length() > 255 ? memo.substring(0, 255) : memo;
    }
}
