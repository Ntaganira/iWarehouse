package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.AlertState;
import com.ntaganira.heritier.iWarehouse.entity.CreditNote;
import com.ntaganira.heritier.iWarehouse.entity.EbmReceipt;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.EbmReceiptStatus;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import com.ntaganira.heritier.iWarehouse.repository.EbmDeviceRepository;
import com.ntaganira.heritier.iWarehouse.repository.EbmReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : EbmServiceTest.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Issuing queues the receipt with the next EBM number (TAX-02); a credit note names its sale's number,
 *               and none is queued for an invoice EBM never had; the first print is the original, later ones copies;
 *               the backlog alert is raised once and cleared (TAX-03).
 * </pre>
 */
class EbmServiceTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 14, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(KIGALI).toInstant(), KIGALI);

    private EbmReceiptRepository repo;
    private AlertStateRepository alerts;
    private DocumentNumberService numbers;
    private ApplicationEventPublisher events;
    private Notifier notifier;
    private EbmService service;
    private final Map<String, AlertState> states = new HashMap<>();

    @BeforeEach
    void setUp() {
        repo = mock(EbmReceiptRepository.class);
        alerts = mock(AlertStateRepository.class);
        numbers = mock(DocumentNumberService.class);
        events = mock(ApplicationEventPublisher.class);
        notifier = mock(Notifier.class);
        service = new EbmService(repo, mock(EbmDeviceRepository.class), alerts, numbers, mock(EbmSettings.class), events, notifier, CLOCK);
        when(repo.save(any(EbmReceipt.class))).thenAnswer(a -> {
            EbmReceipt r = a.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(alerts.findById(anyString())).thenAnswer(a -> Optional.ofNullable(states.get(a.<String>getArgument(0))));
        when(alerts.save(any(AlertState.class))).thenAnswer(a -> {
            AlertState s = a.getArgument(0);
            states.put(s.getKey(), s);
            return s;
        });
    }

    private static SalesInvoice invoice() {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setNumber("INV-WH-2026-000001");
        invoice.setPurchaseCode("A1B2C3");
        return invoice;
    }

    @Test
    void issuingQueuesTheReceiptWithTheNextEbmNumberAndTriesItAfterTheCommit() {
        when(numbers.nextSerial(DocumentType.EBM_INVOICE)).thenReturn(15L, 16L);
        SalesInvoice invoice = invoice();

        EbmReceipt sale = service.queueSale(invoice);

        assertThat(sale.getInvcNo()).isEqualTo(15);
        assertThat(sale.getReceiptType()).isEqualTo("S");
        assertThat(sale.getStatus()).isEqualTo(EbmReceiptStatus.QUEUED);
        assertThat(sale.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(sale.getPurchaseCode()).isEqualTo("A1B2C3");
        assertThat(sale.getDocumentNumber()).isEqualTo("INV-WH-2026-000001");
        verify(events).publishEvent(new EbmService.Queued(sale.getId()));

        CreditNote note = new CreditNote();
        note.setId(UUID.randomUUID());
        note.setNumber("CN-WH-2026-000001");
        note.setInvoice(invoice);
        when(repo.findSale(invoice.getId())).thenReturn(Optional.of(sale));
        EbmReceipt refund = service.queueRefund(note).orElseThrow();
        assertThat(refund.getInvcNo()).isEqualTo(16);
        assertThat(refund.getReceiptType()).isEqualTo("R");
        assertThat(refund.getOrgInvcNo()).isEqualTo(15L);
        assertThat(refund.getCreditNoteId()).isEqualTo(note.getId());
        assertThat(refund.getPurchaseCode()).isNull();

        SalesInvoice before = invoice();                                              // issued before the EBM connection
        CreditNote old = new CreditNote();
        old.setInvoice(before);
        when(repo.findSale(before.getId())).thenReturn(Optional.empty());
        assertThat(service.queueRefund(old)).isEmpty();
        verify(numbers, times(2)).nextSerial(DocumentType.EBM_INVOICE);
    }

    @Test
    void theFirstPrintIsTheOriginalAndLaterOnesAreCopies() {
        UUID invoiceId = UUID.randomUUID();
        EbmReceipt receipt = new EbmReceipt();
        receipt.setId(UUID.randomUUID());
        when(repo.findSale(invoiceId)).thenReturn(Optional.of(receipt));
        when(repo.lockById(receipt.getId())).thenReturn(Optional.of(receipt));

        assertThat(service.printInvoice(invoiceId)).isEqualTo(EbmService.Print.UNSIGNED);   // queued: not fiscal yet
        receipt.setStatus(EbmReceiptStatus.SIGNED);
        assertThat(service.printInvoice(invoiceId)).isEqualTo(EbmService.Print.ORIGINAL);
        assertThat(receipt.getPrintedAt()).isEqualTo(NOW);
        assertThat(service.printInvoice(invoiceId)).isEqualTo(EbmService.Print.COPY);
        assertThat(service.printInvoice(invoiceId)).isEqualTo(EbmService.Print.COPY);
        assertThat(receipt.getCopies()).isEqualTo(2);
        assertThat(service.printInvoice(UUID.randomUUID())).isEqualTo(EbmService.Print.UNSIGNED);

        assertThatThrownBy(() -> service.retry(receipt.getId())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("ebm.retry.signed"));
    }

    @Test
    void theBacklogAlertIsRaisedOnceAndClearedWhenNothingWaits() {
        when(repo.countUnsignedBefore(NOW.minusHours(1))).thenReturn(3L, 3L, 0L);
        when(repo.oldestUnsigned()).thenReturn(NOW.minusHours(5));

        assertThat(service.checkBacklog(Duration.ofHours(1))).isTrue();
        assertThat(service.checkBacklog(Duration.ofHours(1))).isFalse();             // told once
        verify(notifier, times(1)).holders(eq("ALERT_EBM"), isNull(), eq(NotificationKind.EBM), eq("notify.ebm.backlog.title"),
                eq("notify.ebm.backlog.message"), eq("/ebm?show=unsigned"), eq("3"), eq("10/10/2026 09:00"));
        assertThat(service.checkBacklog(Duration.ofHours(1))).isFalse();
        assertThat(states.get("EBM_BACKLOG").getClearedAt()).isEqualTo(NOW);
    }
}
