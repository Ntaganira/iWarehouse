package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.CurrencyRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The posting matrix (SRS 4.9.1) and AT-10: after a full test day (opening stock, a crate received with a broken
 * sheet, import bills in USD and RWF, a cut, write-offs and a unit found, a claim opened and settled) every journal
 * balances, the books balance, and the inventory account equals the stock valuation (m² held x MAC, rounded per
 * glass) after each event, the moving-average rounding going to Inventory Revaluation.
 */
class PostingServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final Map<UUID, BigDecimal> held = new HashMap<>();
    private final List<Journal> posted = new ArrayList<>();
    private Product clear6;
    private Supplier shandong;
    private Supplier bollore;
    private PostingService postings;

    @BeforeEach
    void setUp() {
        clear6 = new Product();
        clear6.setId(UUID.randomUUID());
        clear6.setCode("CLR-6");
        shandong = supplier("Shandong Glass");
        bollore = supplier("Bollore Logistics");

        StockService stockService = mock(StockService.class);
        when(stockService.heldArea(any())).thenAnswer(a -> held.getOrDefault(a.getArgument(0), BigDecimal.ZERO));
        ProductRepository productRepo = mock(ProductRepository.class);
        when(productRepo.findAll()).thenReturn(List.of(clear6));
        when(productRepo.lockAllById(any())).thenReturn(List.of(clear6));
        CurrencyRepository currencyRepo = mock(CurrencyRepository.class);
        Currency rwf = new Currency();
        rwf.setCode("RWF");
        rwf.setBaseCurrency(true);
        when(currencyRepo.findByBaseCurrencyTrue()).thenReturn(Optional.of(rwf));

        // An in-memory ledger: posted journals, the inventory account per glass, journals of a source
        JournalService journals = mock(JournalService.class);
        when(journals.post(any())).thenAnswer(a -> {
            Journal j = a.getArgument(0);
            if (j.isEmpty()) {
                return null;
            }
            assertThat(j.isBalanced()).as("journal for %s balances", j.sourceNumber()).isTrue();
            posted.add(j);
            JournalEntry e = new JournalEntry();
            e.setNumber(String.format("JV-WH-2026-%06d", posted.size()));
            e.setSourceType(j.source());
            return e;
        });
        when(journals.inventoryByProduct()).thenAnswer(a -> {
            Map<UUID, BigDecimal> balance = new HashMap<>();
            lines(AccountKey.INVENTORY).forEach(l -> balance.merge(l.productId(), l.signed(), BigDecimal::add));
            return balance;
        });
        when(journals.openingStock()).thenAnswer(a -> posted.stream().filter(j -> j.source() == JournalSource.OPENING_STOCK)
                .findFirst().map(j -> {
                    JournalEntry e = new JournalEntry();
                    e.setNumber("JV-WH-2026-000001");
                    return e;
                }));
        when(journals.hasJournal(any(), any())).thenAnswer(a -> posted.stream()
                .anyMatch(j -> j.source() == a.getArgument(0) && a.getArgument(1).equals(j.sourceId())));
        postings = new PostingService(journals, stockService, productRepo, currencyRepo, CLOCK);
    }

    @Test
    void aFullTestDayKeepsTheBooksBalancedAndTheInventoryAccountEqualToTheValuation() {
        // Opening: 100 m² of CLR-6 at 5,000 RWF/m²
        held.put(clear6.getId(), new BigDecimal("100.0000"));
        clear6.setMacPerM2(new BigDecimal("5000.0000"));
        postings.openingStock();
        assertThat(balance(AccountKey.INVENTORY)).isEqualByComparingTo("500000.00");
        assertThat(balance(AccountKey.OPENING_EQUITY)).isEqualByComparingTo("-500000.00");
        assertInventoryEqualsValuation();

        // A crate of 20 good sheets and 1 broken, 3210 x 2250 at USD 4.50/m², rate 1,449.123456
        GoodsReceipt receipt = receipt(20, 1, "4.50", "1449.123456");
        CrateBatch crate = receipt.getCrates().get(0);
        BigDecimal unitCost = Costing.unitCost(crate.getSheetArea(), crate.getCostPerM2());
        assertThat(unitCost).isEqualByComparingTo("47098.32");
        PostingService.StockValues before = postings.stockValues(List.of(clear6));
        BigDecimal good = unitCost.multiply(BigDecimal.valueOf(20));
        clear6.setMacPerM2(Costing.movingAverage(held(), clear6.getMacPerM2(), crate.getArea(), good));
        held.put(clear6.getId(), held().add(crate.getArea()));
        postings.goodsReceipt(receipt, before);
        Journal received = last();
        assertThat(line(received, AccountKey.GRNI).credit()).isEqualByComparingTo("989064.72"); // 21 sheets invoiced
        assertThat(line(received, AccountKey.GRNI).fx()).isEqualTo(new Journal.Fx("USD", new BigDecimal("682.53"), new BigDecimal("1449.123456")));
        assertThat(line(received, AccountKey.GRNI).supplierId()).isEqualTo(shandong.getId());
        assertThat(line(received, AccountKey.SPOILAGE).debit()).isEqualByComparingTo("47098.32");  // broken on arrival
        assertThat(line(received, AccountKey.INVENTORY).debit().subtract(good).abs()).isLessThanOrEqualTo(new BigDecimal("0.10"));
        assertInventoryEqualsValuation();

        // Import bills: freight USD 1,000 from Bollore (payable), duty RWF 300,000 paid without a supplier (accrued)
        Shipment shipment = new Shipment();
        shipment.setId(UUID.randomUUID());
        shipment.setNumber("SHP-WH-2026-000005");
        ShipmentCost freight = bill(bollore, "USD", "1000.00", "1449.123456", "BL-778");
        ShipmentCost duty = bill(null, "RWF", "300000.00", "1", null);
        List<BigDecimal> base = List.of(new BigDecimal("1449123.456"), new BigDecimal("300000"));
        BigDecimal total = new BigDecimal("1749123");                          // rounded once (RWF, no decimals)
        BigDecimal brokenShare = new BigDecimal("83291.57");                   // the broken sheet's part, to spoilage
        BigDecimal toStock = total.subtract(brokenShare);
        before = postings.stockValues(List.of(clear6));
        clear6.setMacPerM2(Costing.addValue(held(), clear6.getMacPerM2(), toStock));
        postings.shipmentPosting(shipment, 1, List.of(freight, duty), base, total, BigDecimal.ZERO, brokenShare, before);
        Journal landed = last();
        assertThat(line(landed, AccountKey.PAYABLE).credit()).isEqualByComparingTo("1449123.00"); // what is left over goes to the largest
        assertThat(line(landed, AccountKey.PAYABLE).fx()).isEqualTo(new Journal.Fx("USD", new BigDecimal("1000.00"), new BigDecimal("1449.123456")));
        assertThat(line(landed, AccountKey.PAYABLE).memo()).isEqualTo("BL-778");
        assertThat(line(landed, AccountKey.IMPORT_ACCRUAL).credit()).isEqualByComparingTo("300000.00");
        assertThat(line(landed, AccountKey.IMPORT_ACCRUAL).fx()).isNull();                        // RWF bill: no foreign amount
        assertThat(line(landed, AccountKey.SPOILAGE).debit()).isEqualByComparingTo("83291.57");
        assertInventoryEqualsValuation();

        // A cut: 0.4225 m² of cullet at the sheet's cost per m², 2,755.57 RWF to spoilage
        CuttingJob job = new CuttingJob();
        job.setId(UUID.randomUUID());
        job.setNumber("CUT-WH-2026-000009");
        BigDecimal spoilage = new BigDecimal("2755.57");
        before = postings.stockValues(List.of(clear6));
        clear6.setMacPerM2(Costing.afterCut(held(), clear6.getMacPerM2(), new BigDecimal("-0.4225"), spoilage.negate()));
        held.put(clear6.getId(), held().subtract(new BigDecimal("0.4225")));
        postings.cut(job, spoilage, before);
        assertThat(line(last(), AccountKey.SPOILAGE).debit()).isEqualByComparingTo("2755.57");
        assertInventoryEqualsValuation();

        // An adjustment: a damaged piece (spoilage), a missing one and one found again (adjustment expense)
        StockAdjustment adjustment = adjustment(
                adjustmentLine(AdjustmentKind.WRITE_OFF, WriteOffCause.DAMAGED, "-30000.00"),
                adjustmentLine(AdjustmentKind.WRITE_OFF, WriteOffCause.MISSING, "-15000.00"),
                adjustmentLine(AdjustmentKind.FOUND, null, "12000.00"));
        before = postings.stockValues(List.of(clear6));
        clear6.setMacPerM2(Costing.afterStockChange(held(), clear6.getMacPerM2(), new BigDecimal("-5.0000"), new BigDecimal("-33000.00")));
        held.put(clear6.getId(), held().subtract(new BigDecimal("5.0000")));
        postings.adjustment(adjustment, before);
        assertThat(line(last(), AccountKey.SPOILAGE).debit()).isEqualByComparingTo("30000.00");
        assertThat(line(last(), AccountKey.STOCK_ADJUSTMENT).debit()).isEqualByComparingTo("3000.00"); // 15,000 missing less 12,000 found
        assertInventoryEqualsValuation();

        // The claim for the broken sheet: 120,000 claimed, 100,000 received by bank
        shipment.setClaimParty("Shandong Glass");
        shipment.setClaimDate(LocalDate.of(2026, 10, 9));
        shipment.setClaimAmount(new BigDecimal("120000.00"));
        postings.claimOpened(shipment);
        shipment.setClaimSettledAmount(new BigDecimal("100000.00"));
        shipment.setClaimReceivedInto(ClaimSettlement.BANK);
        postings.claimSettled(shipment);
        assertThat(balance(AccountKey.CLAIMS)).isEqualByComparingTo("0.00");
        assertThat(balance(AccountKey.BANK)).isEqualByComparingTo("100000.00");

        // AT-10: the books balance, the inventory account equals the valuation, every amount is where it belongs
        BigDecimal debits = posted.stream().map(Journal::debits).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = posted.stream().map(Journal::credits).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits);
        assertInventoryEqualsValuation();
        assertThat(balance(AccountKey.GRNI)).isEqualByComparingTo("-989064.72");
        assertThat(balance(AccountKey.SPOILAGE)).isEqualByComparingTo("63145.46"); // 47,098.32 + 83,291.57 + 2,755.57 + 30,000 - 100,000 recovered
        assertThat(balance(AccountKey.STOCK_REVALUATION).abs()).isLessThan(new BigDecimal("1.00")); // rounding only
        assertThat(posted).extracting(Journal::source).containsExactly(JournalSource.OPENING_STOCK, JournalSource.GOODS_RECEIPT,
                JournalSource.SHIPMENT, JournalSource.CUTTING_JOB, JournalSource.ADJUSTMENT, JournalSource.CLAIM_OPENED,
                JournalSource.CLAIM_SETTLED);
    }

    @Test
    void theLedgerStartsOnceAndCountsWhatWasPostedBeforeIt() {
        // A receipt posted before the opening journal: the opening brings the account to the value, not past it
        held.put(clear6.getId(), new BigDecimal("50.0000"));
        clear6.setMacPerM2(new BigDecimal("4000.0000"));
        GoodsReceipt receipt = receipt(10, 0, "3.00", "1400.000000");
        CrateBatch crate = receipt.getCrates().get(0);
        PostingService.StockValues before = postings.stockValues(List.of(clear6));
        clear6.setMacPerM2(Costing.movingAverage(held(), clear6.getMacPerM2(), crate.getArea(),
                Costing.unitCost(crate.getSheetArea(), crate.getCostPerM2()).multiply(BigDecimal.TEN)));
        held.put(clear6.getId(), held().add(crate.getArea()));
        postings.goodsReceipt(receipt, before);

        postings.openingStock();

        assertInventoryEqualsValuation();
        assertThatThrownBy(() -> postings.openingStock()).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("journal.opening.exists"));
    }

    @Test
    void nothingToOpenWithoutStock() {
        assertThatThrownBy(() -> postings.openingStock()).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("journal.opening.nothing"));
    }

    @Test
    void claimsOpenedBeforeTheLedgerPostNothingWhenDecided() {
        Shipment shipment = new Shipment();
        shipment.setId(UUID.randomUUID());
        shipment.setNumber("SHP-WH-2026-000003");
        shipment.setClaimAmount(new BigDecimal("50000.00"));
        shipment.setClaimSettledAmount(new BigDecimal("50000.00"));
        shipment.setClaimReceivedInto(ClaimSettlement.BANK);

        assertThat(postings.claimSettled(shipment)).isNull();
        assertThat(postings.claimRejected(shipment)).isNull();
        assertThat(posted).isEmpty();
    }

    @Test
    void aRejectedClaimAndAClaimSettledAboveItsAmountGoToSpoilage() {
        Shipment shipment = new Shipment();
        shipment.setId(UUID.randomUUID());
        shipment.setNumber("SHP-WH-2026-000006");
        shipment.setClaimParty("Insurer");
        shipment.setClaimDate(LocalDate.of(2026, 10, 9));
        shipment.setClaimAmount(new BigDecimal("40000.00"));
        postings.claimOpened(shipment);
        postings.claimRejected(shipment);
        assertThat(balance(AccountKey.CLAIMS)).isEqualByComparingTo("0");
        assertThat(balance(AccountKey.SPOILAGE)).isEqualByComparingTo("0");   // recovery expected, then lost again

        Shipment other = new Shipment();
        other.setId(UUID.randomUUID());
        other.setNumber("SHP-WH-2026-000007");
        other.setClaimParty("Supplier");
        other.setClaimDate(LocalDate.of(2026, 10, 9));
        other.setClaimAmount(new BigDecimal("10000.00"));
        postings.claimOpened(other);
        other.setClaimSettledAmount(new BigDecimal("12000.00"));
        other.setClaimReceivedInto(ClaimSettlement.PAYABLE);              // credited against what we owe
        postings.claimSettled(other);
        assertThat(line(last(), AccountKey.PAYABLE).debit()).isEqualByComparingTo("12000.00");
        assertThat(line(last(), AccountKey.SPOILAGE).credit()).isEqualByComparingTo("2000.00");
        assertThat(balance(AccountKey.CLAIMS)).isEqualByComparingTo("0");
    }

    @Test
    void aCounterSaleAndItsTillKeepTheBooksBalancedAndTheStockAtItsValue() {   // AT-08, AT-10
        held.put(clear6.getId(), new BigDecimal("100.0000"));
        clear6.setMacPerM2(new BigDecimal("5000.0000"));
        postings.openingStock();

        TillSession till = new TillSession();
        till.setId(UUID.randomUUID());
        till.setNumber("TILL-WH-2026-000001");
        till.setCashierUsername("cashier1");
        till.setOpenedAt(LocalDate.of(2026, 10, 9).atTime(8, 0));
        till.setOpeningFloat(new BigDecimal("20000.00"));
        postings.tillOpened(till);
        assertThat(balance(AccountKey.CASH)).isEqualByComparingTo("20000");
        assertThat(balance(AccountKey.CASH_VAULT)).isEqualByComparingTo("-20000");

        // A 7.2225 m² sheet sold for 195,008 RWF VAT included: half cash, half mobile money
        Customer walkIn = new Customer();
        walkIn.setId(UUID.randomUUID());
        walkIn.setName("Walk-in customer");
        SalesInvoice invoice = new SalesInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setNumber("INV-WH-2026-000001");
        invoice.setCustomer(walkIn);
        invoice.setInvoiceDate(LocalDate.of(2026, 10, 9));
        invoice.setNetAmount(new BigDecimal("165261.02"));
        invoice.setVatAmount(new BigDecimal("29746.98"));
        invoice.setTotalAmount(new BigDecimal("195008.00"));
        PostingService.StockValues before = postings.stockValues(List.of(clear6));
        held.put(clear6.getId(), held().subtract(new BigDecimal("7.2225")));          // sold: the MAC stays
        postings.sale(invoice, List.of(salePayment(PaymentMethod.CASH, "97504", null), salePayment(PaymentMethod.MOBILE_MONEY, "97504", "MP-55")),
                before);
        Journal sale = last();
        assertThat(line(sale, AccountKey.CASH).debit()).isEqualByComparingTo("97504");
        assertThat(line(sale, AccountKey.MOBILE_MONEY).debit()).isEqualByComparingTo("97504");
        assertThat(line(sale, AccountKey.MOBILE_MONEY).memo()).isEqualTo("MP-55");
        assertThat(line(sale, AccountKey.SALES).credit()).isEqualByComparingTo("165261.02");
        assertThat(line(sale, AccountKey.VAT_OUTPUT).credit()).isEqualByComparingTo("29746.98");
        assertThat(line(sale, AccountKey.COGS).debit()).isEqualByComparingTo("36112.50");   // 7.2225 m² x 5,000 MAC
        assertThat(line(sale, AccountKey.INVENTORY).credit()).isEqualByComparingTo("36112.50");
        assertInventoryEqualsValuation();

        // A credit sale names its customer on the receivable line
        Customer builders = new Customer();
        builders.setId(UUID.randomUUID());
        builders.setName("Umucyo Builders");
        SalesInvoice onCredit = new SalesInvoice();
        onCredit.setId(UUID.randomUUID());
        onCredit.setNumber("INV-WH-2026-000002");
        onCredit.setCustomer(builders);
        onCredit.setInvoiceDate(LocalDate.of(2026, 10, 9));
        onCredit.setNetAmount(new BigDecimal("8474.58"));
        onCredit.setVatAmount(new BigDecimal("1525.42"));
        onCredit.setTotalAmount(new BigDecimal("10000.00"));
        before = postings.stockValues(List.of(clear6));
        held.put(clear6.getId(), held().subtract(new BigDecimal("0.5000")));
        postings.sale(onCredit, List.of(salePayment(PaymentMethod.CREDIT, "10000", null)), before);
        assertThat(line(last(), AccountKey.RECEIVABLE).customerId()).isEqualTo(builders.getId());
        assertInventoryEqualsValuation();

        // An order of sizes to cut paid by a deposit (POS-08): the whole sale is revenue, the balance a receivable
        SalesInvoice order = new SalesInvoice();
        order.setId(UUID.randomUUID());
        order.setNumber("INV-WH-2026-000003");
        order.setCustomer(walkIn);
        order.setBuyerName("Jean Habimana");
        order.setInvoiceDate(LocalDate.of(2026, 10, 9));
        order.setNetAmount(new BigDecimal("11440.68"));
        order.setVatAmount(new BigDecimal("2059.32"));
        order.setTotalAmount(new BigDecimal("13500.00"));
        order.setBalanceDue(new BigDecimal("6500.00"));
        postings.sale(order, List.of(salePayment(PaymentMethod.CASH, "7000", null)), postings.stockValues(List.of(clear6)));
        Journal deposit = last();
        assertThat(line(deposit, AccountKey.CASH).debit()).isEqualByComparingTo("7000");
        assertThat(line(deposit, AccountKey.RECEIVABLE).debit()).isEqualByComparingTo("6500");
        assertThat(line(deposit, AccountKey.RECEIVABLE).customerId()).isEqualTo(walkIn.getId());
        assertThat(line(deposit, AccountKey.SALES).credit()).isEqualByComparingTo("11440.68");
        assertThat(deposit.debits()).isEqualByComparingTo(deposit.credits());

        // Its balance paid at collection: Dr Cash / Cr the customer's receivable, which is then clear
        postings.saleBalance(order, List.of(salePayment(PaymentMethod.CASH, "6500", null)));
        Journal balance = last();
        assertThat(balance.source()).isEqualTo(JournalSource.SALES_BALANCE);
        assertThat(line(balance, AccountKey.CASH).debit()).isEqualByComparingTo("6500");
        assertThat(line(balance, AccountKey.RECEIVABLE).credit()).isEqualByComparingTo("6500");
        assertThat(lines(AccountKey.RECEIVABLE).stream().filter(l -> walkIn.getId().equals(l.customerId()))
                .map(Journal.Line::signed).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
        assertInventoryEqualsValuation();

        // The till closes 1,000 short: the vault gets what was counted, the shortage goes to Cash Over/Short
        till.setClosedAt(LocalDate.of(2026, 10, 9).atTime(18, 0));
        till.setExpectedCash(new BigDecimal("131004.00"));                              // float + 97,504 + 7,000 + 6,500
        till.setCountedCash(new BigDecimal("130004.00"));
        postings.tillClosed(till);
        assertThat(balance(AccountKey.CASH)).isEqualByComparingTo("0");
        assertThat(balance(AccountKey.CASH_VAULT)).isEqualByComparingTo("110004");     // 130,004 back less the 20,000 float
        assertThat(balance(AccountKey.CASH_OVER_SHORT)).isEqualByComparingTo("1000");
        BigDecimal debits = posted.stream().map(Journal::debits).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = posted.stream().map(Journal::credits).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits);
    }

    @Test
    void aCreditNoteReversesTheSaleAndPutsGlassBackAtItsOwnCost() {   // POS-09, AT-10
        held.put(clear6.getId(), new BigDecimal("100.0000"));
        clear6.setMacPerM2(new BigDecimal("5000.0000"));
        postings.openingStock();

        Customer walkIn = new Customer();
        walkIn.setId(UUID.randomUUID());
        walkIn.setName("Walk-in customer");
        SalesInvoice invoice = new SalesInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setNumber("INV-WH-2026-000001");
        invoice.setCustomer(walkIn);
        CreditNote note = new CreditNote();
        note.setId(UUID.randomUUID());
        note.setNumber("CN-WH-2026-000001");
        note.setInvoice(invoice);
        note.setCustomer(walkIn);
        note.setCreditDate(LocalDate.of(2026, 10, 9));
        note.setReason("Wrong size ordered");
        note.setNetAmount(new BigDecimal("165261.02"));
        note.setVatAmount(new BigDecimal("29746.98"));
        note.setTotalAmount(new BigDecimal("195008.00"));
        note.setBalanceReduced(new BigDecimal("0.00"));
        note.setRefundMethod(PaymentMethod.CASH);
        note.setRefundAmount(new BigDecimal("195008.00"));

        // A 7.2225 m² sheet back on its rack at its own cost (36,500): the MAC moves; a piece back as cullet (1,200)
        PostingService.StockValues before = postings.stockValues(List.of(clear6));
        clear6.setMacPerM2(Costing.afterStockChange(held(), clear6.getMacPerM2(), new BigDecimal("7.2225"), new BigDecimal("36500.00")));
        held.put(clear6.getId(), held().add(new BigDecimal("7.2225")));
        postings.creditNote(note, new BigDecimal("36500.00"), new BigDecimal("1200.00"), before);

        Journal credit = last();
        assertThat(credit.source()).isEqualTo(JournalSource.CREDIT_NOTE);
        assertThat(line(credit, AccountKey.SALES_RETURNS).debit()).isEqualByComparingTo("165261.02");
        assertThat(line(credit, AccountKey.VAT_OUTPUT).debit()).isEqualByComparingTo("29746.98");
        assertThat(line(credit, AccountKey.CASH).credit()).isEqualByComparingTo("195008");
        assertThat(line(credit, AccountKey.COGS).credit()).isEqualByComparingTo("37700");   // both costs back from COGS
        assertThat(line(credit, AccountKey.SPOILAGE).debit()).isEqualByComparingTo("1200");
        assertThat(credit.debits()).isEqualByComparingTo(credit.credits());
        assertInventoryEqualsValuation();

        // To the customer's account: the receivable line names them
        CreditNote onAccount = new CreditNote();
        onAccount.setId(UUID.randomUUID());
        onAccount.setNumber("CN-WH-2026-000002");
        onAccount.setInvoice(invoice);
        onAccount.setCustomer(walkIn);
        onAccount.setCreditDate(LocalDate.of(2026, 10, 9));
        onAccount.setReason("Overcharged piece");
        onAccount.setNetAmount(new BigDecimal("8474.58"));
        onAccount.setVatAmount(new BigDecimal("1525.42"));
        onAccount.setTotalAmount(new BigDecimal("10000.00"));
        onAccount.setBalanceReduced(new BigDecimal("4000.00"));
        onAccount.setRefundMethod(PaymentMethod.CREDIT);
        onAccount.setRefundAmount(new BigDecimal("6000.00"));
        postings.creditNote(onAccount, BigDecimal.ZERO, new BigDecimal("500.00"), postings.stockValues(List.of(clear6)));
        assertThat(lines(AccountKey.RECEIVABLE).stream().filter(l -> walkIn.getId().equals(l.customerId()))
                .map(Journal.Line::signed).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("-10000");
        assertThat(last().debits()).isEqualByComparingTo(last().credits());
        assertInventoryEqualsValuation();
    }

    // ---------------------------------------------------------------- helpers

    private static SalesPayment salePayment(PaymentMethod method, String amount, String reference) {
        SalesPayment p = new SalesPayment();
        p.setMethod(method);
        p.setAmount(new BigDecimal(amount));
        p.setReference(reference);
        return p;
    }

    private void assertInventoryEqualsValuation() {
        BigDecimal valuation = held().multiply(clear6.getMacPerM2()).setScale(2, RoundingMode.HALF_UP);
        assertThat(balance(AccountKey.INVENTORY)).as("inventory account = m² held x MAC").isEqualByComparingTo(valuation);
    }

    private BigDecimal held() {
        return held.getOrDefault(clear6.getId(), BigDecimal.ZERO);
    }

    private List<Journal.Line> lines(AccountKey account) {
        return posted.stream().flatMap(j -> j.lines().stream()).filter(l -> l.account() == account).toList();
    }

    private BigDecimal balance(AccountKey account) {
        return lines(account).stream().map(Journal.Line::signed).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Journal last() {
        return posted.get(posted.size() - 1);
    }

    private static Journal.Line line(Journal journal, AccountKey account) {
        return journal.lines().stream().filter(l -> l.account() == account).findFirst()
                .orElseThrow(() -> new AssertionError("no " + account + " line"));
    }

    private static Supplier supplier(String name) {
        Supplier s = new Supplier();
        s.setId(UUID.randomUUID());
        s.setName(name);
        return s;
    }

    private GoodsReceipt receipt(int sheets, int broken, String pricePerM2, String rate) {
        PurchaseOrder order = new PurchaseOrder();
        order.setSupplier(shandong);
        order.setCurrencyCode("USD");
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setPricePerM2(new BigDecimal(pricePerM2));
        CrateBatch crate = new CrateBatch();
        crate.setProduct(clear6);
        crate.setPoLine(line);
        crate.setWidthMm(3210);
        crate.setHeightMm(2250);
        crate.setSheets(sheets);
        crate.setBroken(broken);
        crate.setCostPerM2(Costing.costPerM2(line.getPricePerM2(), new BigDecimal(rate)));
        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setId(UUID.randomUUID());
        receipt.setNumber("GRN-WH-2026-000010");
        receipt.setPurchaseOrder(order);
        receipt.setReceivedDate(LocalDate.of(2026, 10, 9));
        receipt.setRate(new BigDecimal(rate));
        receipt.getCrates().add(crate);
        return receipt;
    }

    private static ShipmentCost bill(Supplier supplier, String currency, String amount, String rate, String ref) {
        ShipmentCost cost = new ShipmentCost();
        cost.setSupplier(supplier);
        cost.setCurrencyCode(currency);
        cost.setAmount(new BigDecimal(amount));
        cost.setRate(new BigDecimal(rate));
        cost.setInvoiceRef(ref);
        return cost;
    }

    private static StockAdjustment adjustment(StockAdjustmentLine... lines) {
        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setId(UUID.randomUUID());
        adjustment.setNumber("ADJ-WH-2026-000009");
        adjustment.getLines().addAll(List.of(lines));
        return adjustment;
    }

    private static StockAdjustmentLine adjustmentLine(AdjustmentKind kind, WriteOffCause cause, String value) {
        StockAdjustmentLine line = new StockAdjustmentLine();
        line.setKind(kind);
        line.setCause(cause);
        line.setValueChange(new BigDecimal(value));
        return line;
    }
}
