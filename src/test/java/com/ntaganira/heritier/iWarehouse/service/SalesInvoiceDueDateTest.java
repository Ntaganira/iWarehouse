package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The due date a sale is shown with (TASK.md): its day plus the customer's terms and any approved extension. */
class SalesInvoiceDueDateTest {

    @Test
    void theDueDateFollowsTheCustomersTermsAndAnApprovedExtension() {
        Customer customer = new Customer();
        customer.setPaymentTermsDays(30);

        SalesInvoice invoice = new SalesInvoice();
        invoice.setCustomer(customer);
        invoice.setInvoiceDate(LocalDate.of(2026, 10, 31));
        assertThat(invoice.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 30));

        invoice.setExtraTermsDays(15);
        assertThat(invoice.getDueDate()).isEqualTo(LocalDate.of(2026, 12, 15));
    }
}
