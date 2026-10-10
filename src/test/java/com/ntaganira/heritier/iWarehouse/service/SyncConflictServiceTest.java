package com.ntaganira.heritier.iWarehouse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.entity.SyncConflict;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.SyncConflictRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SyncConflictServiceTest.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A sale kept for the supervisor read back from what the phone sent (SYNC-05): each unit as it is now (or
 *               unknown), the customer by name, the payments; nothing when what came cannot be read as a sale.
 * </pre>
 */
class SyncConflictServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final StockUnit unit = new StockUnit();
    private final Customer customer = new Customer();
    private SyncConflictService service;

    @BeforeEach
    void setUp() {
        unit.setId(UUID.randomUUID());
        unit.setCode("U-WH-000046");
        customer.setId(UUID.randomUUID());
        customer.setName("Umucyo Builders Ltd");
        StockUnitRepository units = mock(StockUnitRepository.class);
        when(units.findByIdIn(any())).thenAnswer(a -> a.<Collection<UUID>>getArgument(0).contains(unit.getId()) ? List.of(unit) : List.of());
        CustomerRepository customers = mock(CustomerRepository.class);
        when(customers.findById(any())).thenAnswer(a -> customer.getId().equals(a.getArgument(0)) ? Optional.of(customer) : Optional.empty());
        service = new SyncConflictService(mock(SyncConflictRepository.class), units, customers, mapper, Clock.systemUTC());
    }

    @Test
    void theSaleIsReadBackWithTheUnitsAsTheyAreNow() throws Exception {
        UUID unknown = UUID.randomUUID();
        MobileSaleService.SaleRequest req = new MobileSaleService.SaleRequest(UUID.randomUUID(), "MINV-WH-2026-000016", UUID.randomUUID(),
                customer.getId(), "Site office", "100200301", OffsetDateTime.parse("2026-10-10T21:45:23Z"),
                List.of(new MobileSaleService.LineRequest(unit.getId(), new BigDecimal("27500"), "Corners protected", new BigDecimal("198619")),
                        new MobileSaleService.LineRequest(unknown, new BigDecimal("27000"), null, new BigDecimal("1000"))),
                List.of(new MobileSaleService.PaymentRequest(PaymentMethod.CASH, new BigDecimal("100000"), null),
                        new MobileSaleService.PaymentRequest(PaymentMethod.MOBILE_MONEY, new BigDecimal("99619"), "MP-9")),
                new BigDecimal("199619"), new BigDecimal("100000"));
        SyncConflict conflict = new SyncConflict();
        conflict.setPayload(mapper.writeValueAsString(req));

        SyncConflictService.SentSale sent = service.sent(conflict).orElseThrow();
        assertThat(sent.customer()).isEqualTo("Umucyo Builders Ltd");
        assertThat(sent.buyerName()).isEqualTo("Site office");
        assertThat(sent.buyerTin()).isEqualTo("100200301");
        assertThat(sent.lines()).hasSize(2);
        assertThat(sent.lines().get(0).unit()).isSameAs(unit);
        assertThat(sent.lines().get(0).priceReason()).isEqualTo("Corners protected");
        assertThat(sent.lines().get(1).unit()).isNull();                 // a unit the server does not know
        assertThat(sent.lines().get(1).unitId()).isEqualTo(unknown);
        assertThat(sent.payments()).extracting(MobileSaleService.PaymentRequest::reference).containsExactly(null, "MP-9");
        assertThat(sent.cashTendered()).isEqualByComparingTo("100000");
    }

    @Test
    void whatCannotBeReadAsASaleGivesNothing() {
        SyncConflict garbled = new SyncConflict();
        garbled.setPayload("{\"clientId\": \"not-a-uuid\", \"lines\": 3");
        assertThat(service.sent(garbled)).isEmpty();

        SyncConflict bare = new SyncConflict();
        bare.setPayload("{}");
        assertThat(service.sent(bare)).get().satisfies(s -> {
            assertThat(s.customer()).isNull();
            assertThat(s.lines()).isEmpty();
            assertThat(s.payments()).isEmpty();
        });
    }
}
