package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.ProcessingServiceDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.ChargeUnit;
import com.ntaganira.heritier.iWarehouse.enums.GlassType;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Price lists, prices and the price a customer pays (MD-06). */
class PriceListServiceTest {

    private PriceListRepository listRepo;
    private PriceListItemRepository itemRepo;
    private ProcessingServiceRepository serviceRepo;
    private ServicePriceRepository servicePriceRepo;
    private ProductRepository productRepo;
    private CustomerRepository customerRepo;
    private SettingService settings;
    private PriceListService service;

    private final PriceList retail = list("RETAIL", true);
    private final PriceList contractors = list("CONTRACTOR", false);
    private final Product clear6 = product("CLR-6");
    private final Product clear8 = product("CLR-8");

    @BeforeEach
    void setUp() {
        listRepo = mock(PriceListRepository.class);
        itemRepo = mock(PriceListItemRepository.class);
        serviceRepo = mock(ProcessingServiceRepository.class);
        servicePriceRepo = mock(ServicePriceRepository.class);
        productRepo = mock(ProductRepository.class);
        customerRepo = mock(CustomerRepository.class);
        settings = mock(SettingService.class);
        when(settings.getDecimal(SettingKey.MIN_CHARGEABLE_AREA)).thenReturn(new BigDecimal("0.25"));
        when(listRepo.findByDefaultListTrue()).thenReturn(Optional.of(retail));
        when(listRepo.findById(retail.getId())).thenReturn(Optional.of(retail));
        when(listRepo.findById(contractors.getId())).thenReturn(Optional.of(contractors));
        when(productRepo.findById(clear6.getId())).thenReturn(Optional.of(clear6));
        when(productRepo.findById(clear8.getId())).thenReturn(Optional.of(clear8));
        when(itemRepo.findByPriceList_IdAndProduct_Id(any(), any())).thenReturn(Optional.empty());
        when(itemRepo.findByPriceList_Id(any())).thenReturn(List.of());
        when(servicePriceRepo.findByPriceList_Id(any())).thenReturn(List.of());
        service = new PriceListService(listRepo, itemRepo, serviceRepo, servicePriceRepo, productRepo, customerRepo,
                settings, mock(DataChangeService.class));
    }

    @Test
    void aCustomerPaysTheirListPriceElseTheDefaultPrice() {
        price(retail, clear6, "25000");
        price(contractors, clear6, "22000");
        price(retail, clear8, "33000");
        Customer contractor = customer(contractors);

        assertThat(service.priceFor(contractor, clear6)).hasValueSatisfying(p -> {
            assertThat(p.pricePerM2()).isEqualByComparingTo("22000");
            assertThat(p.list()).isSameAs(contractors);
            assertThat(p.fromDefaultList()).isFalse();
        });
        assertThat(service.priceFor(contractor, clear8)).hasValueSatisfying(p -> {
            assertThat(p.pricePerM2()).isEqualByComparingTo("33000");
            assertThat(p.list()).isSameAs(retail);
            assertThat(p.fromDefaultList()).isTrue();
            assertThat(p.includesVat()).isTrue();
        });
        assertThat(service.priceFor(customer(null), clear6)).hasValueSatisfying(p -> assertThat(p.list()).isSameAs(retail));
    }

    @Test
    void aProductNoListPricesHasNoPrice() {
        assertThat(service.priceFor(customer(contractors), clear6)).isEmpty();
        assertThat(service.priceFor(null, clear6)).isEmpty();
    }

    @Test
    void savingPricesTouchesOnlyRowsThatChange() {
        PriceListItem six = item(retail, clear6, "25000");
        PriceListItem eight = item(retail, clear8, "33000");
        when(itemRepo.findByPriceList_Id(retail.getId())).thenReturn(List.of(six, eight));

        Map<UUID, BigDecimal> prices = new HashMap<>();
        prices.put(clear6.getId(), new BigDecimal("25000.00")); // same price, other scale
        prices.put(clear8.getId(), null);                       // cleared
        PriceListService.PriceUpdate update = service.updatePrices(retail.getId(), prices, Map.of());

        assertThat(update).isEqualTo(new PriceListService.PriceUpdate(0, 0, 1));
        assertThat(eight.getPricePerM2()).isNull(); // the row stays, so its history stays
        verify(itemRepo, never()).save(any());
    }

    @Test
    void savingPricesAddsMissingRowsAndCountsChanges() {
        PriceListItem six = item(contractors, clear6, "22000");
        when(itemRepo.findByPriceList_Id(contractors.getId())).thenReturn(List.of(six));

        Map<UUID, BigDecimal> prices = new LinkedHashMap<>();
        prices.put(clear6.getId(), new BigDecimal("21500"));
        prices.put(clear8.getId(), new BigDecimal("30000"));
        PriceListService.PriceUpdate update = service.updatePrices(contractors.getId(), prices, Map.of());

        assertThat(update).isEqualTo(new PriceListService.PriceUpdate(1, 1, 0));
        assertThat(six.getPricePerM2()).isEqualByComparingTo("21500");
        verify(itemRepo).save(argThat(i -> i.getProduct() == clear8 && i.getPricePerM2().compareTo(new BigDecimal("30000")) == 0));
        assertThat(PriceListService.describe(update)).isEqualTo("1 added, 1 changed");
    }

    @Test
    void theDefaultListStaysActiveAndAListInUseCannotBeDeactivated() {
        assertThatThrownBy(() -> service.setEnabled(retail.getId(), false)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("pricelist.default.disable"));

        when(customerRepo.countByPriceList_IdAndEnabledTrue(contractors.getId())).thenReturn(3L);
        assertThatThrownBy(() -> service.setEnabled(contractors.getId(), false)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getMessageKey()).isEqualTo("pricelist.disable.customers");
            assertThat(e.getArgs()).containsExactly("CONTRACTOR", 3L);
        });
        assertThat(contractors.isEnabled()).isTrue();
    }

    @Test
    void makingADefaultClearsTheOldOneFirst() {
        service.makeDefault(contractors.getId());

        assertThat(retail.isDefaultList()).isFalse();
        assertThat(contractors.isDefaultList()).isTrue();
        verify(listRepo).saveAndFlush(retail);
    }

    @Test
    void anInactiveListCannotBecomeTheDefault() {
        contractors.setEnabled(false);
        assertThatThrownBy(() -> service.makeDefault(contractors.getId())).isInstanceOf(BusinessException.class);
        assertThat(retail.isDefaultList()).isTrue();
    }

    @Test
    void minimumAreaComesFromTheListElseSettings() {
        assertThat(service.minChargeableArea(retail)).isEqualByComparingTo("0.25");
        contractors.setMinChargeableM2(new BigDecimal("0.5"));
        assertThat(service.minChargeableArea(contractors)).isEqualByComparingTo("0.5");
    }

    @Test
    void aPricedServiceKeepsItsChargeUnit() {
        ProcessingService drilling = new ProcessingService();
        drilling.setId(UUID.randomUUID());
        drilling.setCode("DRILLING");
        drilling.setName("Drilling");
        drilling.setChargeUnit(ChargeUnit.HOLE);
        when(serviceRepo.findById(drilling.getId())).thenReturn(Optional.of(drilling));
        when(servicePriceRepo.existsByService_IdAndPriceIsNotNull(drilling.getId())).thenReturn(true);
        ProcessingServiceDto dto = new ProcessingServiceDto();
        dto.setName("Drilling");
        dto.setChargeUnit(ChargeUnit.PIECE);

        assertThatThrownBy(() -> service.updateService(drilling.getId(), dto)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("chargeUnit"));

        dto.setName("Drilling (per hole)");
        dto.setChargeUnit(ChargeUnit.HOLE);
        service.updateService(drilling.getId(), dto);
        assertThat(drilling.getName()).isEqualTo("Drilling (per hole)");
    }

    // ---------------------------------------------------------------- helpers

    private void price(PriceList list, Product product, String price) {
        PriceListItem item = item(list, product, price);
        when(itemRepo.findByPriceList_IdAndProduct_Id(list.getId(), product.getId())).thenReturn(Optional.of(item));
    }

    private static PriceListItem item(PriceList list, Product product, String price) {
        PriceListItem item = new PriceListItem();
        item.setId(UUID.randomUUID());
        item.setPriceList(list);
        item.setProduct(product);
        item.setPricePerM2(new BigDecimal(price));
        return item;
    }

    private static Customer customer(PriceList list) {
        Customer c = new Customer();
        c.setPriceList(list);
        return c;
    }

    private static PriceList list(String code, boolean isDefault) {
        PriceList l = new PriceList();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setName(code);
        l.setDefaultList(isDefault);
        return l;
    }

    private static Product product(String code) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setGlassType(GlassType.CLEAR);
        p.setThicknessMm(new BigDecimal(code.substring(4)));
        return p;
    }
}
