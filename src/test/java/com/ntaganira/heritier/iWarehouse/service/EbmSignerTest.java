package com.ntaganira.heritier.iWarehouse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntaganira.heritier.iWarehouse.ebm.SimulatedVsdc;
import com.ntaganira.heritier.iWarehouse.ebm.Vsdc;
import com.ntaganira.heritier.iWarehouse.ebm.VsdcClient;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : EbmSignerTest.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : AT-09: EBM down during a sale, the sale is complete and its receipt queued, retried with backoff and
 *               signed automatically when EBM is back (TAX-03). Missing settings and a refund before its sale wait;
 *               a refusal fails the receipt and tells the alert's holders.
 * </pre>
 */
class EbmSignerTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 14, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(KIGALI).toInstant(), KIGALI);

    private EbmReceiptRepository receipts;
    private EbmItemRepository items;
    private SalesInvoiceRepository invoices;
    private SalesPaymentRepository payments;
    private EbmSettings ebmSettings;
    private Notifier notifier;
    private SimulatedVsdc simulator;
    private EbmSigner signer;
    private EbmSettings.Config config;

    private SalesInvoice invoice;
    private EbmReceipt receipt;

    @BeforeEach
    void setUp() {
        receipts = mock(EbmReceiptRepository.class);
        items = mock(EbmItemRepository.class);
        invoices = mock(SalesInvoiceRepository.class);
        payments = mock(SalesPaymentRepository.class);
        ebmSettings = mock(EbmSettings.class);
        notifier = mock(Notifier.class);
        TaxCategoryRepository taxes = mock(TaxCategoryRepository.class);
        simulator = new SimulatedVsdc(receipts, new ObjectMapper(), CLOCK);
        signer = new EbmSigner(receipts, items, mock(EbmDeviceRepository.class), invoices, payments, mock(CreditNoteRepository.class),
                mock(CreditNoteLineRepository.class), taxes, ebmSettings, notifier, CLOCK);

        config = config("3017170000");
        when(ebmSettings.config()).thenAnswer(a -> config);
        when(ebmSettings.client(any())).thenReturn(simulator);
        when(receipts.maxSimulatedReceiptNo()).thenReturn(26L);
        when(items.nextSerial()).thenReturn(1L);
        TaxCategory standard = new TaxCategory();
        standard.setEbmCode("B");
        standard.setRate(new BigDecimal("18.00"));
        when(taxes.findAllByOrderByEnabledDescCodeAsc()).thenReturn(List.of(standard));

        Product clear6 = new Product();
        clear6.setId(UUID.randomUUID());
        clear6.setCode("CLR-6");
        clear6.setGlassType(GlassType.CLEAR);
        clear6.setThicknessMm(new BigDecimal("6.00"));
        Customer walkIn = new Customer();
        walkIn.setName("Walk-in customer");
        invoice = new SalesInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setNumber("INV-WH-2026-000001");
        invoice.setCustomer(walkIn);
        invoice.setBuyerName("Jean Habimana");
        invoice.setPostedAt(NOW.minusMinutes(1));
        invoice.setInvoiceDate(NOW.toLocalDate());
        invoice.setPostedBy("cashier1");
        invoice.setTotalAmount(new BigDecimal("195008.00"));
        SalesInvoiceLine line = new SalesInvoiceLine();
        line.setInvoice(invoice);
        line.setLineNo(1);
        line.setProduct(clear6);
        line.setWidthMm(3210);
        line.setHeightMm(2250);
        line.setChargeableAreaM2(new BigDecimal("7.2225"));
        line.setPricePerM2(new BigDecimal("27000.00"));
        line.setTaxCode("B");
        line.setVatRate(new BigDecimal("18.00"));
        line.setAmount(new BigDecimal("195008.00"));
        invoice.getLines().add(line);
        when(invoices.findDetailedById(invoice.getId())).thenReturn(Optional.of(invoice));
        SalesPayment cash = new SalesPayment();
        cash.setMethod(PaymentMethod.CASH);
        cash.setAmount(new BigDecimal("195008.00"));
        when(payments.findByInvoiceIdOrderByLineNo(invoice.getId())).thenReturn(List.of(cash));

        receipt = new EbmReceipt();
        receipt.setId(UUID.randomUUID());
        receipt.setInvcNo(15);
        receipt.setReceiptType("S");
        receipt.setInvoiceId(invoice.getId());
        receipt.setDocumentNumber(invoice.getNumber());
        receipt.setNextAttemptAt(NOW);
        when(receipts.lockForSigning(receipt.getId())).thenReturn(Optional.of(receipt));
    }

    private static EbmSettings.Config config(String glassClass) {
        return new EbmSettings.Config(EbmMode.SIMULATOR, "100200300", "00", null, null, glassClass, "7213150000", "RW",
                "https://myrra.rra.gov.rw/common/link/ebm/receipt/indexEbmReceiptData?Data=", "Glass Rwanda Ltd", "Kigali");
    }

    @Test
    void ebmDownLeavesTheReceiptQueuedAndItIsSignedOnRecovery() {
        simulator.setDown(true);

        assertThat(signer.attempt(receipt.getId())).isEqualTo(EbmSigner.Attempt.UNREACHABLE);
        assertThat(receipt.getStatus()).isEqualTo(EbmReceiptStatus.QUEUED);
        assertThat(receipt.getAttempts()).isEqualTo(1);
        assertThat(receipt.getResultCode()).isEqualTo("NET");
        assertThat(receipt.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(signer.attempt(receipt.getId())).isEqualTo(EbmSigner.Attempt.UNREACHABLE);
        assertThat(receipt.getNextAttemptAt()).isEqualTo(NOW.plusMinutes(1));          // backoff doubles
        verifyNoInteractions(notifier);

        simulator.setDown(false);
        assertThat(signer.attempt(receipt.getId())).isEqualTo(EbmSigner.Attempt.SIGNED);

        assertThat(receipt.getStatus()).isEqualTo(EbmReceiptStatus.SIGNED);
        assertThat(receipt.getAttempts()).isEqualTo(3);
        assertThat(receipt.getRcptNo()).isEqualTo(27L);                               // after the simulator's last receipt
        assertThat(receipt.getTotRcptNo()).isEqualTo(27L);
        assertThat(receipt.getReceiptLabel()).isEqualTo("27/27 NS");
        assertThat(receipt.receiptLabel(true)).isEqualTo("27/27 CS");
        assertThat(receipt.getRcptSign()).hasSize(16);
        assertThat(receipt.getIntrlData()).hasSize(26);
        assertThat(receipt.getSdcId()).isEqualTo(SimulatedVsdc.SDC_ID);
        assertThat(receipt.getMrcNo()).isEqualTo(SimulatedVsdc.MRC_NO);
        assertThat(receipt.isSimulated()).isTrue();
        assertThat(receipt.getSignedAt()).isEqualTo(NOW);
        assertThat(receipt.getNextAttemptAt()).isNull();
        assertThat(receipt.getResultCode()).isEqualTo("000");
        assertThat(receipt.getRequestJson()).contains("\"invcNo\":15").contains("\"rcptTyCd\":\"S\"").contains("\"totAmt\":195008.00");

        ArgumentCaptor<EbmItem> item = ArgumentCaptor.forClass(EbmItem.class);
        verify(items).save(item.capture());                                           // the glass registered first
        assertThat(item.getValue().getItemCode()).isEqualTo("RW2NTXM2X0000001");
        assertThat(item.getValue().getItemClass()).isEqualTo("3017170000");
        assertThat(item.getValue().getTaxCode()).isEqualTo("B");
        assertThat(item.getValue().isSimulated()).isTrue();

        assertThat(signer.attempt(receipt.getId())).isEqualTo(EbmSigner.Attempt.SKIPPED);   // signed: never sent again
    }

    @Test
    void aMissingSettingWaitsAndARefundWaitsForItsSale() {
        config = config(null);

        assertThat(signer.attempt(receipt.getId())).isEqualTo(EbmSigner.Attempt.WAITING);
        assertThat(receipt.getResultCode()).isEqualTo("CFG");
        assertThat(receipt.getLastError()).contains("ebm.glass-item-class");
        assertThat(receipt.getStatus()).isEqualTo(EbmReceiptStatus.QUEUED);

        EbmReceipt refund = new EbmReceipt();
        refund.setId(UUID.randomUUID());
        refund.setInvcNo(16);
        refund.setReceiptType("R");
        refund.setOrgInvcNo(15L);
        refund.setInvoiceId(invoice.getId());
        refund.setCreditNoteId(UUID.randomUUID());
        refund.setDocumentNumber("CN-WH-2026-000001");
        when(receipts.lockForSigning(refund.getId())).thenReturn(Optional.of(refund));
        when(receipts.findSale(invoice.getId())).thenReturn(Optional.of(receipt));

        assertThat(signer.attempt(refund.getId())).isEqualTo(EbmSigner.Attempt.WAITING);
        assertThat(refund.getResultCode()).isEqualTo("WAIT");
        assertThat(refund.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRefusalFailsTheReceiptAndTellsTheAlertsHolders() {
        VsdcClient vsdc = mock(VsdcClient.class);
        when(ebmSettings.client(any())).thenReturn(vsdc);
        when(vsdc.saveItem(any())).thenReturn(new Vsdc.Reply<JsonNode>("{}", "{}", new Vsdc.Envelope<>("000", "It is succeeded", null, null)));
        when(vsdc.saveSale(any())).thenReturn(new Vsdc.Reply<Vsdc.Signature>("{\"invcNo\":15}", "{\"resultCd\":\"881\"}",
                new Vsdc.Envelope<>("881", "Purchase code is mandatory", null, null)));

        assertThat(signer.attempt(receipt.getId())).isEqualTo(EbmSigner.Attempt.FAILED);

        assertThat(receipt.getStatus()).isEqualTo(EbmReceiptStatus.FAILED);
        assertThat(receipt.getResultCode()).isEqualTo("881");
        assertThat(receipt.getLastError()).isEqualTo("Purchase code is mandatory");
        assertThat(receipt.getNextAttemptAt()).isNull();
        assertThat(receipt.getResponseJson()).contains("881");
        verify(notifier).holders(eq("ALERT_EBM"), isNull(), eq(NotificationKind.EBM), eq("notify.ebm.failed.title"),
                eq("notify.ebm.failed.message"), eq("/ebm/" + receipt.getId()), eq("INV-WH-2026-000001"), eq("Purchase code is mandatory"));
        assertThat(signer.attempt(receipt.getId())).isEqualTo(EbmSigner.Attempt.SKIPPED);   // waits for a person
    }
}
