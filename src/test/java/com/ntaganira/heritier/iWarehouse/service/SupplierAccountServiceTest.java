package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Supplier accounts (ACC-08, ACC-09): an invoice bills goods receipts not invoiced at their GRNI values and must match the
 * supplier's total; a payment settles the oldest items in its currency and books the realised FX gain or loss.
 */
class SupplierAccountServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private Supplier shandong;
    private GoodsReceipt grn1;
    private GoodsReceipt grn2;
    private final List<JournalLine> payable = new ArrayList<>();
    private final List<SupplierInvoiceLine> billed = new ArrayList<>();
    private SupplierInvoiceRepository invoiceRepo;
    private PostingService postings;
    private ExchangeRateService rates;
    private SupplierAccountService service;

    @BeforeEach
    void setUp() {
        AppUserPrincipal principal = new AppUserPrincipal(7L, "accountant", "accountant", "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        shandong = new Supplier();
        shandong.setId(UUID.randomUUID());
        shandong.setName("Shandong Float Glass Co.");
        shandong.setCurrencyCode("USD");
        shandong.setPaymentTermsDays(30);
        grn1 = receipt("GRN-WH-2026-000001", "2026-09-01");
        grn2 = receipt("GRN-WH-2026-000002", "2026-09-20");

        invoiceRepo = mock(SupplierInvoiceRepository.class);
        when(invoiceRepo.save(any())).thenAnswer(a -> {
            SupplierInvoice i = a.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });
        SupplierInvoiceLineRepository lineRepo = mock(SupplierInvoiceLineRepository.class);
        when(lineRepo.save(any())).thenAnswer(a -> {
            billed.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(lineRepo.findByGoodsReceiptIdIn(any())).thenAnswer(a -> billed);
        SupplierPaymentRepository paymentRepo = mock(SupplierPaymentRepository.class);
        when(paymentRepo.save(any())).thenAnswer(a -> {
            SupplierPayment p = a.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        SupplierRepository supplierRepo = mock(SupplierRepository.class);
        when(supplierRepo.lockById(shandong.getId())).thenReturn(Optional.of(shandong));
        GoodsReceiptRepository receiptRepo = mock(GoodsReceiptRepository.class);
        when(receiptRepo.findByPurchaseOrder_Supplier_IdAndStatusOrderByReceivedDateAscNumberAsc(shandong.getId(), GoodsReceiptStatus.POSTED))
                .thenReturn(List.of(grn1, grn2));
        CurrencyRepository currencyRepo = mock(CurrencyRepository.class);
        Currency rwf = new Currency();
        rwf.setCode("RWF");
        when(currencyRepo.findByBaseCurrencyTrue()).thenReturn(Optional.of(rwf));
        JournalService journals = mock(JournalService.class);
        when(journals.supplierLines(shandong.getId())).thenAnswer(a -> payable);
        // What each receipt left on GRNI: 3,000 USD at 1,300 and 1,500 USD at 1,350
        when(journals.grniOfReceipts(anyList())).thenAnswer(a -> List.of(
                grniLine(grn1, "3000", "1300", "3900000"), grniLine(grn2, "1500", "1350", "2025000")));
        rates = mock(ExchangeRateService.class);
        when(rates.rateFor(eq("USD"), any())).thenReturn(new ExchangeRateService.AppliedRate("USD", new BigDecimal("1340"),
                LocalDate.of(2026, 10, 9), RateSource.BNR));
        postings = mock(PostingService.class);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.SUPPLIER_INVOICE)).thenReturn("SINV-WH-2026-000001");
        when(numbers.next(DocumentType.SUPPLIER_PAYMENT)).thenReturn("SPAY-WH-2026-000001");
        service = new SupplierAccountService(invoiceRepo, lineRepo, paymentRepo, supplierRepo, receiptRepo, currencyRepo, journals, rates,
                postings, numbers, CLOCK);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anInvoiceBillsReceiptsAtWhatTheyLeftOnGrniAndMustMatchTheSuppliersTotal() {
        assertThat(service.uninvoiced(shandong)).extracting(u -> u.receipt().getNumber()).containsExactly("GRN-WH-2026-000001", "GRN-WH-2026-000002");
        List<UUID> both = List.of(grn1.getId(), grn2.getId());
        assertThatThrownBy(() -> service.recordInvoice(shandong.getId(), form("INV-778", "4400", both)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("amount");
                    assertThat(e.getMessageKey()).isEqualTo("supplierInvoice.amount.mismatch");
                    assertThat((BigDecimal) e.getArgs()[0]).isEqualByComparingTo("4500");
                });
        assertThatThrownBy(() -> service.recordInvoice(shandong.getId(), form("INV-778", "4500", List.of())))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("supplierInvoice.receipts.required"));

        SupplierInvoice invoice = service.recordInvoice(shandong.getId(), form(" INV-778 ", "4500", both));

        assertThat(invoice.getSupplierRef()).isEqualTo("INV-778");
        assertThat(invoice.getCurrencyCode()).isEqualTo("USD");
        assertThat(invoice.getAmount()).isEqualByComparingTo("4500");
        assertThat(invoice.getBaseAmount()).isEqualByComparingTo("5925000");
        assertThat(invoice.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 8));       // 30 days
        assertThat(billed).extracting(SupplierInvoiceLine::getRate).extracting(BigDecimal::intValue).containsExactly(1300, 1350);
        verify(postings).supplierInvoice(eq(invoice), any());
        assertThat(service.uninvoiced(shandong)).isEmpty();                            // each receipt billed once

        when(invoiceRepo.existsBySupplier_IdAndSupplierRefIgnoreCase(shandong.getId(), "inv-778")).thenReturn(true);
        assertThatThrownBy(() -> service.recordInvoice(shandong.getId(), form("inv-778", "1", both)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("supplierInvoice.ref.taken"));
    }

    @Test
    void aPaymentSettlesTheOldestItemsAndBooksTheRealisedFx() {
        payable.add(apLine("2026-09-05", "USD", "3000", "1300", "3900000"));          // the invoice's two receipts
        payable.add(apLine("2026-09-25", "USD", "1500", "1350", "2025000"));
        assertThat(service.open(shandong).get("USD").getAmount()).isEqualByComparingTo("4500");

        assertThatThrownBy(() -> service.pay(shandong.getId(), new SupplierAccountService.PaymentForm("USD", new BigDecimal("4600"),
                PaymentMethod.BANK_TRANSFER, "SWIFT-1", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("supplierPayment.tooMuch"));
        assertThatThrownBy(() -> service.pay(shandong.getId(), new SupplierAccountService.PaymentForm("EUR", BigDecimal.TEN,
                PaymentMethod.BANK_TRANSFER, "SWIFT-1", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("supplierPayment.nothingOwed"));
        assertThatThrownBy(() -> service.pay(shandong.getId(), new SupplierAccountService.PaymentForm("USD", BigDecimal.TEN,
                PaymentMethod.BANK_TRANSFER, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("supplierPayment.refRequired"));

        // 3,500 USD at today's 1,340: all of the first (3,900,000) and 500 of the second (675,000)
        SupplierPayment payment = service.pay(shandong.getId(), new SupplierAccountService.PaymentForm("USD", new BigDecimal("3500"),
                PaymentMethod.BANK_TRANSFER, "SWIFT-1", null));

        assertThat(payment.getRate()).isEqualByComparingTo("1340");
        assertThat(payment.getBaseAmount()).isEqualByComparingTo("4690000");
        assertThat(payment.getSettledBase()).isEqualByComparingTo("4575000");
        assertThat(payment.getFxGainLoss()).isEqualByComparingTo("-115000");           // paid more RWF than booked: a loss
        assertThat(payment.getRateSource()).isEqualTo(RateSource.BNR);
        verify(postings).supplierPayment(payment);
    }

    @Test
    void aPaymentInRwfHasNoRateAndNoFx() {
        payable.add(apLine("2026-09-25", "RWF", "500000", null, "500000"));            // a freight bill in RWF
        SupplierPayment cash = service.pay(shandong.getId(), new SupplierAccountService.PaymentForm("RWF", new BigDecimal("200000"),
                PaymentMethod.CASH, null, "Freight, first half"));
        assertThat(cash.getRate()).isNull();
        assertThat(cash.getBaseAmount()).isEqualByComparingTo("200000");
        assertThat(cash.getFxGainLoss()).isEqualByComparingTo("0");
        assertThat(cash.getReference()).isNull();
        verify(rates, never()).rateFor(any(), any());
    }

    @Test
    void aMonthEndRevaluationAndItsReversalLeaveWhatIsOwedAsBooked() {   // ACC-08
        payable.add(apLine("2026-09-05", "USD", "3000", "1300", "3900000"));          // due 05/10: 4 days late on 09/10
        payable.add(revaluationLine("2026-09-30", "0", "60000"));                      // revalued at 1,320: 60,000 more
        payable.add(revaluationLine("2026-10-01", "60000", "0"));                      // reversed the next day

        Payables.Open usd = service.open(shandong).get("USD");
        assertThat(usd.getAmount()).isEqualByComparingTo("3000");
        assertThat(usd.getBase()).isEqualByComparingTo("3900000");                     // as booked: realised when paid
        SupplierAccountService.Account account = service.account(shandong);
        assertThat(account.statement()).hasSize(3);                                    // the statement shows both journals
        assertThat(account.balance()).isEqualByComparingTo("3900000");
        assertThat(account.ageing().get(Ageing.Bucket.DAYS_1_30)).isEqualByComparingTo("3900000");   // the reversal settles nothing
        assertThat(account.ageing().get(Ageing.Bucket.NOT_DUE)).isEqualByComparingTo("0");
    }

    private static JournalLine revaluationLine(String date, String debit, String credit) {
        JournalEntry entry = new JournalEntry();
        entry.setEntryDate(LocalDate.parse(date));
        entry.setSourceType(JournalSource.FX_REVALUATION);
        JournalLine line = new JournalLine();
        line.setEntry(entry);
        line.setDebit(new BigDecimal(debit));
        line.setCredit(new BigDecimal(credit));
        line.setCurrencyCode("USD");
        line.setFxAmount(BigDecimal.ZERO);
        line.setRate(new BigDecimal("1320"));
        return line;
    }

    private static SupplierAccountService.InvoiceForm form(String ref, String amount, List<UUID> receipts) {
        return new SupplierAccountService.InvoiceForm(ref, LocalDate.of(2026, 10, 9), new BigDecimal(amount), receipts, null);
    }

    private GoodsReceipt receipt(String number, String date) {
        GoodsReceipt r = new GoodsReceipt();
        r.setId(UUID.randomUUID());
        r.setNumber(number);
        r.setReceivedDate(LocalDate.parse(date));
        r.setStatus(GoodsReceiptStatus.POSTED);
        return r;
    }

    private static JournalLine grniLine(GoodsReceipt receipt, String fx, String rate, String base) {
        JournalEntry entry = new JournalEntry();
        entry.setSourceType(JournalSource.GOODS_RECEIPT);
        entry.setSourceId(receipt.getId());
        entry.setEntryDate(receipt.getReceivedDate());
        JournalLine line = new JournalLine();
        line.setEntry(entry);
        line.setCredit(new BigDecimal(base));
        line.setCurrencyCode("USD");
        line.setFxAmount(new BigDecimal(fx));
        line.setRate(new BigDecimal(rate));
        return line;
    }

    private static JournalLine apLine(String date, String currency, String fx, String rate, String base) {
        JournalEntry entry = new JournalEntry();
        entry.setEntryDate(LocalDate.parse(date));
        entry.setSourceType(JournalSource.SUPPLIER_INVOICE);
        JournalLine line = new JournalLine();
        line.setEntry(entry);
        line.setCredit(new BigDecimal(base));
        if (!"RWF".equals(currency)) {
            line.setCurrencyCode(currency);
            line.setFxAmount(new BigDecimal(fx));
            line.setRate(new BigDecimal(rate));
        }
        return line;
    }
}
