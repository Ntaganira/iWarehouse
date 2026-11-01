package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The sale confirmation, off the request thread and never in the sale's way (TASK.md). */
class WhatsAppNotifierTest {

    private SalesInvoiceRepository invoiceRepo;
    private SettingService settings;
    private WhatsAppGateway gateway;
    private WhatsAppNotifier notifier;
    private SalesInvoice invoice;

    @BeforeEach
    void setUp() {
        invoiceRepo = mock(SalesInvoiceRepository.class);
        settings = mock(SettingService.class);
        gateway = mock(WhatsAppGateway.class);
        MessageSource messages = mock(MessageSource.class);
        when(settings.getBoolean(SettingKey.WHATSAPP_ENABLED)).thenReturn(true);
        when(settings.get(SettingKey.COMPANY_NAME)).thenReturn("iWarehouse");
        when(gateway.configured()).thenReturn(true);
        when(messages.getMessage(eq("whatsapp.sale.message"), any(Object[].class), any(Locale.class))).thenReturn("Thank you");
        notifier = new WhatsAppNotifier(invoiceRepo, settings, gateway, messages);

        Customer customer = new Customer();
        customer.setId(UUID.randomUUID());
        customer.setName("Umucyo Ltd");
        customer.setWhatsappNumber("+250788533669");
        customer.setPaymentTermsDays(30);
        invoice = new SalesInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setNumber("INV-WH-2026-000001");
        invoice.setCustomer(customer);
        invoice.setInvoiceDate(LocalDate.of(2026, 10, 31));
        invoice.setTotalAmount(new BigDecimal("118000.00"));
        invoice.setStatus(SalesInvoiceStatus.POSTED);
    }

    @Test
    void aPostedSaleIsSentToTheCustomersWhatsAppNumber() {
        when(invoiceRepo.findDetailedById(invoice.getId())).thenReturn(Optional.of(invoice));

        notifier.saleIssued(invoice.getId());

        verify(gateway).send(eq("250788533669"), anyString());
    }

    @Test
    void nothingIsSentWhenTurnedOffWithoutANumberOrForADraft() {
        when(settings.getBoolean(SettingKey.WHATSAPP_ENABLED)).thenReturn(false);
        when(invoiceRepo.findDetailedById(invoice.getId())).thenReturn(Optional.of(invoice));
        notifier.saleIssued(invoice.getId());
        verify(gateway, never()).send(anyString(), anyString());

        when(settings.getBoolean(SettingKey.WHATSAPP_ENABLED)).thenReturn(true);
        invoice.getCustomer().setWhatsappNumber(null);
        invoice.getCustomer().setPhone("0788533669");
        invoice.setStatus(SalesInvoiceStatus.DRAFT);
        notifier.saleIssued(invoice.getId());
        verify(gateway, never()).send(anyString(), anyString());
    }

    @Test
    void aFailingGatewayNeverThrowsBackIntoTheSale() {
        when(invoiceRepo.findDetailedById(invoice.getId())).thenReturn(Optional.of(invoice));
        doThrow(new IllegalStateException("gateway down")).when(gateway).send(anyString(), anyString());

        assertThatCode(() -> notifier.saleIssued(invoice.getId())).doesNotThrowAnyException();
    }
}
