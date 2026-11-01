package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The overdue flags of a customer page (TASK.md): past due beyond the grace, from the receivable entries. */
class CustomerAgeingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-15T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private JournalService journalService;
    private CustomerAgeing ageing;

    @BeforeEach
    void setUp() {
        journalService = mock(JournalService.class);
        CustomerAgeing service = new CustomerAgeing(journalService, mock(SalesInvoiceRepository.class), CLOCK);
        ageing = service;
    }

    @Test
    void onlyWhatIsPastDueBeyondTheGraceIsFlagged() {
        Customer late = customer("Umucyo", 30);
        Customer current = customer("Walk-in", 30);
        Object[] charge = {late.getId(), LocalDate.of(2026, 1, 1), new BigDecimal("1000.00"), BigDecimal.ZERO};
        Object[] recent = {current.getId(), LocalDate.of(2026, 3, 1), new BigDecimal("500.00"), BigDecimal.ZERO};
        when(journalService.receivableEntries()).thenReturn(List.<Object[]>of(charge, recent));

        Map<UUID, CustomerAgeing.Overdue> flags = ageing.overdue(List.of(late, current), 10);

        assertThat(flags).containsOnlyKeys(late.getId());
        assertThat(flags.get(late.getId()).amount()).isEqualByComparingTo("1000.00");
        assertThat(flags.get(late.getId()).oldestDays()).isEqualTo(43);
    }

    @Test
    void aGraceBeyondTheLatenessKeepsEveryoneOffTheFlag() {
        Customer late = customer("Umucyo", 30);
        Object[] charge = {late.getId(), LocalDate.of(2026, 1, 1), new BigDecimal("1000.00"), BigDecimal.ZERO};
        when(journalService.receivableEntries()).thenReturn(List.<Object[]>of(charge));

        assertThat(ageing.overdue(List.of(late), 60)).isEmpty();
    }

    private static Customer customer(String name, int terms) {
        Customer customer = new Customer();
        customer.setId(UUID.randomUUID());
        customer.setName(name);
        customer.setPaymentTermsDays(terms);
        return customer;
    }
}
