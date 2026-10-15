package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import com.ntaganira.heritier.iWarehouse.config.Countries;
import com.ntaganira.heritier.iWarehouse.dto.CustomerDto;
import com.ntaganira.heritier.iWarehouse.dto.SupplierDto;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.PriceList;
import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import com.ntaganira.heritier.iWarehouse.enums.CustomerType;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.Incoterm;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.SupplierApprovalStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.AttachmentRepository;
import com.ntaganira.heritier.iWarehouse.repository.CurrencyRepository;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.PriceListRepository;
import com.ntaganira.heritier.iWarehouse.repository.PurchaseOrderRepository;
import com.ntaganira.heritier.iWarehouse.repository.SupplierRepository;
import com.ntaganira.heritier.iWarehouse.repository.TerritoryRepository;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Suppliers (MD-05) and customers (MD-04, POS-05). */
class PartyServicesTest {

    private SupplierRepository supplierRepo;
    private PurchaseOrderRepository orderRepo;
    private CurrencyRepository currencyRepo;
    private CustomerRepository customerRepo;
    private PriceListRepository priceListRepo;
    private DocumentNumberService numbers;
    private SupplierService suppliers;
    private CustomerService customers;
    private SettingService settings;
    private AttachmentRepository attachmentRepo;

    private final Currency usd = currency("USD", true);
    private final Currency gbp = currency("GBP", false);
    private final PriceList contractors = priceList("CONTRACTOR", true);
    private final PriceList oldList = priceList("OLD", false);

    @BeforeEach
    void setUp() {
        supplierRepo = mock(SupplierRepository.class);
        currencyRepo = mock(CurrencyRepository.class);
        customerRepo = mock(CustomerRepository.class);
        priceListRepo = mock(PriceListRepository.class);
        numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.SUPPLIER)).thenReturn("SUP-WH-0001");
        when(numbers.next(DocumentType.CUSTOMER)).thenReturn("CUS-WH-00001");
        when(currencyRepo.findByCode("USD")).thenReturn(Optional.of(usd));
        when(currencyRepo.findByCode("GBP")).thenReturn(Optional.of(gbp));
        when(supplierRepo.save(any(Supplier.class))).thenAnswer(i -> i.getArgument(0));
        when(customerRepo.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));
        when(customerRepo.findByTin(any())).thenReturn(Optional.empty());
        when(priceListRepo.findById(contractors.getId())).thenReturn(Optional.of(contractors));
        when(priceListRepo.findById(oldList.getId())).thenReturn(Optional.of(oldList));
        orderRepo = mock(PurchaseOrderRepository.class);
        settings = mock(SettingService.class);
        when(settings.getInt(SettingKey.SUPPLIER_REFERENCES_REQUIRED)).thenReturn(2);
        attachmentRepo = mock(AttachmentRepository.class);
        suppliers = new SupplierService(supplierRepo, currencyRepo, numbers, new Countries(), orderRepo, settings, attachmentRepo,
                mock(Notifier.class), Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneId.of("Africa/Kigali")));
        customers = new CustomerService(customerRepo, priceListRepo, numbers, settings, mock(TerritoryRepository.class));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- the approved supplier register (CMP 08)

    @Test
    void aNewSupplierWaitsUntilAnotherPersonApprovesItWithItsPapers() {
        when(settings.getBoolean(SettingKey.APPROVAL_SUPPLIER_ONBOARDING)).thenReturn(true);
        signIn(3L, "buyer");
        Supplier s = suppliers.create(supplier("Shandong Float Glass", "CN", "USD", null));
        s.setId(UUID.randomUUID());
        s.setCreatedBy("buyer");
        when(supplierRepo.lockById(s.getId())).thenReturn(Optional.of(s));
        when(supplierRepo.findById(s.getId())).thenReturn(Optional.of(s));
        assertThat(s.getApprovalStatus()).isEqualTo(SupplierApprovalStatus.PENDING);
        assertThatThrownBy(() -> SupplierService.requireApproved(s, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("supplier.notApproved"));
        assertThatThrownBy(() -> suppliers.approve(s.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("supplier.approve.own"));

        signIn(9L, "coo");
        assertThat(suppliers.missingForApproval(s)).containsExactly("supplier.missing.registration", "supplier.missing.bank",
                "supplier.missing.references", "supplier.missing.documents");
        assertThatThrownBy(() -> suppliers.approve(s.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("supplier.missing.registration"));
        s.setRegistrationNumber("91370000-X");
        s.setBankName("Bank of China");
        s.setBankAccount("6217 0000 1234");
        s.setTradeReferences("Kigali Glass Traders, +250 788 000 001\nHuye Hardware, +250 788 000 002");
        when(attachmentRepo.countByOwnerTypeAndOwnerIdAndRemovedAtIsNull(AttachmentOwner.SUPPLIER, s.getId())).thenReturn(1L);
        suppliers.approve(s.getId(), "papers checked");
        assertThat(s.isApproved()).isTrue();
        assertThat(s.getApprovedBy()).isEqualTo("coo");

        // New bank details send it back for approval
        SupplierDto dto = supplier("Shandong Float Glass", "CN", "USD", null);
        dto.setRegistrationNumber("91370000-X");
        dto.setBankName("Bank of China");
        dto.setBankAccount("9999 0000 0000");
        dto.setTradeReferences(s.getTradeReferences());
        when(supplierRepo.existsByNameIgnoreCaseAndIdNot(any(), any())).thenReturn(false);
        suppliers.update(s.getId(), dto);
        assertThat(s.getApprovalStatus()).isEqualTo(SupplierApprovalStatus.PENDING);
        assertThat(s.getApprovedBy()).isNull();
    }

    @Test
    void withoutOnboardingANewSupplierIsApprovedAtOnce() {
        Supplier s = suppliers.create(supplier("Kigali Glass Traders", "RW", "USD", null));
        assertThat(s.isApproved()).isTrue();
    }

    // ---------------------------------------------------------------- customers: duplicates and terms (BP 11, F-02)

    @Test
    void aPhoneAlreadyOnAnotherCustomerNeedsAConfirmation() {
        Customer other = new Customer();
        other.setId(UUID.randomUUID());
        other.setCode("CUS-WH-00007");
        other.setName("Umucyo Builders Ltd");
        when(customerRepo.findByPhone("+250 788 123 456")).thenReturn(List.of(other));
        CustomerDto dto = new CustomerDto();
        dto.setType(CustomerType.ACCOUNT);
        dto.setName("Umucyo Construction");
        dto.setPhone("+250 788 123 456");
        assertThatThrownBy(() -> customers.create(dto, false))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("customer.phone.duplicate");
                    assertThat(e.getField()).isEqualTo("phone");
                });
        dto.setConfirmDuplicate(true);
        Customer saved = customers.create(dto, false);
        assertThat(saved.getPhone()).isEqualTo("+250 788 123 456");

        // Saved again with the same phone, it is not asked again
        saved.setId(UUID.randomUUID());
        saved.setType(CustomerType.ACCOUNT);
        when(customerRepo.findWithPriceListById(saved.getId())).thenReturn(Optional.of(saved));
        CustomerDto again = new CustomerDto();
        again.setType(CustomerType.ACCOUNT);
        again.setName("Umucyo Construction Ltd");
        again.setPhone("+250 788 123 456");
        assertThat(customers.update(saved.getId(), again, false).getName()).isEqualTo("Umucyo Construction Ltd");
    }

    @Test
    void withCreditApplicationsTheFormNeverSetsTerms() {
        when(settings.getBoolean(SettingKey.APPROVAL_CREDIT_APPLICATION)).thenReturn(true);
        CustomerDto dto = new CustomerDto();
        dto.setType(CustomerType.ACCOUNT);
        dto.setName("Kigali Glaziers Ltd");
        dto.setCreditLimit(new BigDecimal("2000000"));
        dto.setPaymentTermsDays(30);
        Customer c = customers.create(dto, true);
        assertThat(c.getCreditLimit()).isEqualByComparingTo("0");
        assertThat(c.getPaymentTermsDays()).isZero();
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    // ---------------------------------------------------------------- suppliers

    @Test
    void newSupplierGetsTheNextCodeAndTidiedFields() {
        Supplier s = suppliers.create(supplier(" Shandong Float Glass ", "CN", "USD", " 91370000-X "));

        assertThat(s.getCode()).isEqualTo("SUP-WH-0001");
        assertThat(s.getName()).isEqualTo("Shandong Float Glass");
        assertThat(s.getTin()).isEqualTo("91370000X");
        assertThat(s.getIncoterm()).isEqualTo(Incoterm.FOB);
        assertThat(s.getContactName()).isNull();
    }

    @Test
    void supplierNamesAreUniqueIgnoringCase() {
        when(supplierRepo.existsByNameIgnoreCase("Shandong Float Glass")).thenReturn(true);

        assertField(() -> suppliers.create(supplier("Shandong Float Glass", "CN", "USD", null)), "name", "supplier.name.taken");
        verify(numbers, never()).next(any());
    }

    @Test
    void supplierNeedsARealCountryAndAnActiveCurrency() {
        assertField(() -> suppliers.create(supplier("A", "XX", "USD", null)), "countryCode", "supplier.country.required");
        assertField(() -> suppliers.create(supplier("B", "GB", "GBP", null)), "currencyCode", "supplier.currency.inactive");
    }

    @Test
    void aSupplierKeepsACurrencyDeactivatedSince() {
        Supplier existing = new Supplier();
        existing.setId(UUID.randomUUID());
        existing.setCurrencyCode("GBP");
        when(supplierRepo.findById(existing.getId())).thenReturn(Optional.of(existing));

        suppliers.update(existing.getId(), supplier("Pilkington", "GB", "GBP", null));

        assertThat(existing.getCurrencyCode()).isEqualTo("GBP");
    }

    @Test
    void aRwandanSupplierTinHasNineDigits() {
        assertField(() -> suppliers.create(supplier("Kigali Glass", "RW", "USD", "12345")), "tin", "supplier.tin.rwanda");
        assertThat(suppliers.create(supplier("Kigali Glass", "RW", "USD", "100 200 300")).getTin()).isEqualTo("100200300");
    }

    @Test
    void countriesHaveNamesInTheReadersLanguage() {
        Countries countries = new Countries();
        assertThat(countries.name("CN", Locale.ENGLISH)).isEqualTo("China");
        assertThat(countries.name("CN", Locale.FRENCH)).isEqualTo("Chine");
        assertThat(countries.isValid("RW")).isTrue();
        assertThat(countries.isValid("XX")).isFalse();
    }

    // ---------------------------------------------------------------- customers

    @Test
    void withoutTermsRightsANewCustomerIsCashOnTheDefaultList() {
        CustomerDto dto = customer(CustomerType.ACCOUNT, "Kigali Glaziers", "100200300");
        dto.setCreditLimit(new BigDecimal("2000000"));
        dto.setPaymentTermsDays(30);
        dto.setPriceListId(contractors.getId());

        Customer c = customers.create(dto, false);

        assertThat(c.getCode()).isEqualTo("CUS-WH-00001");
        assertThat(c.getCreditLimit()).isEqualByComparingTo("0");
        assertThat(c.getPaymentTermsDays()).isZero();
        assertThat(c.getPriceList()).isNull();
    }

    @Test
    void withTermsRightsCreditTermsAndListAreSet() {
        CustomerDto dto = customer(CustomerType.CONTRACTOR, "Umucyo Builders", null);
        dto.setCreditLimit(new BigDecimal("5000000"));
        dto.setPaymentTermsDays(30);
        dto.setPriceListId(contractors.getId());

        Customer c = customers.create(dto, true);

        assertThat(c.getCreditLimit()).isEqualByComparingTo("5000000");
        assertThat(c.getPaymentTermsDays()).isEqualTo(30);
        assertThat(c.getPriceList()).isSameAs(contractors);
        assertThat(c.getTin()).isNull();
    }

    @Test
    void walkInCustomersNeverGetCredit() {
        CustomerDto dto = customer(CustomerType.WALK_IN, "Jean Claude", null);
        dto.setCreditLimit(new BigDecimal("100000"));
        dto.setPaymentTermsDays(7);

        Customer c = customers.create(dto, true);

        assertThat(c.getCreditLimit()).isEqualByComparingTo("0");
        assertThat(c.getPaymentTermsDays()).isZero();
    }

    @Test
    void oneCustomerPerTin() {
        Customer other = existingCustomer(CustomerType.ACCOUNT, BigDecimal.ZERO);
        other.setCode("CUS-WH-00007");
        other.setName("Kigali Glaziers");
        when(customerRepo.findByTin("100200300")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> customers.create(customer(CustomerType.ACCOUNT, "KG Ltd", "100200300"), true))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("tin");
                    assertThat(e.getArgs()).containsExactly("100200300", "CUS-WH-00007", "Kigali Glaziers");
                });
        // the same customer keeps its own TIN on edit
        customers.update(other.getId(), customer(CustomerType.ACCOUNT, "Kigali Glaziers", "100200300"), true);
    }

    @Test
    void onlyTermsRightsTurnACreditCustomerIntoAWalkIn() {
        Customer c = existingCustomer(CustomerType.ACCOUNT, new BigDecimal("1000000"));

        assertField(() -> customers.update(c.getId(), customer(CustomerType.WALK_IN, "X", null), false), "type", "customer.type.termsNeeded");
        assertThat(c.getCreditLimit()).isEqualByComparingTo("1000000");

        customers.update(c.getId(), customer(CustomerType.WALK_IN, "X", null), true);
        assertThat(c.getCreditLimit()).isEqualByComparingTo("0");
    }

    @Test
    void editWithoutTermsRightsKeepsTheTerms() {
        Customer c = existingCustomer(CustomerType.ACCOUNT, new BigDecimal("1000000"));
        c.setPriceList(contractors);
        CustomerDto dto = customer(CustomerType.CONTRACTOR, "New name", null);
        dto.setCreditLimit(new BigDecimal("9000000"));

        customers.update(c.getId(), dto, false);

        assertThat(c.getName()).isEqualTo("New name");
        assertThat(c.getCreditLimit()).isEqualByComparingTo("1000000");
        assertThat(c.getPriceList()).isSameAs(contractors);
    }

    @Test
    void anInactiveListCannotBeGivenButCanBeKept() {
        Customer c = existingCustomer(CustomerType.ACCOUNT, BigDecimal.ZERO);
        CustomerDto dto = customer(CustomerType.ACCOUNT, "X", null);
        dto.setPriceListId(oldList.getId());
        assertField(() -> customers.update(c.getId(), dto, true), "priceListId", "customer.priceList.inactive");

        c.setPriceList(oldList);
        customers.update(c.getId(), dto, true);
        assertThat(c.getPriceList()).isSameAs(oldList);
    }

    @Test
    void theCounterDefaultCustomerStaysAnActiveWalkIn() {
        Customer walkIn = existingCustomer(CustomerType.WALK_IN, BigDecimal.ZERO);
        walkIn.setDefaultCustomer(true);
        walkIn.setCode("WALK-IN");

        assertField(() -> customers.update(walkIn.getId(), customer(CustomerType.ACCOUNT, "Walk-in", null), true), "type", "customer.default.type");
        assertThatThrownBy(() -> customers.setEnabled(walkIn.getId(), false)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("customer.default.disable"));
        assertThat(walkIn.isEnabled()).isTrue();
    }

    @Test
    void formOffersActiveListsPlusTheCustomersOwnInactiveOne() {
        when(priceListRepo.findByEnabledTrueOrderByDefaultListDescNameAsc()).thenReturn(List.of(contractors));
        Customer c = existingCustomer(CustomerType.ACCOUNT, BigDecimal.ZERO);
        c.setPriceList(oldList);

        assertThat(customers.priceListsFor(null)).containsExactly(contractors);
        assertThat(customers.priceListsFor(c)).containsExactly(contractors, oldList);
    }

    // ---------------------------------------------------------------- helpers

    private static void assertField(ThrowableAssert.ThrowingCallable call, String field, String key) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getField()).isEqualTo(field);
            assertThat(e.getMessageKey()).isEqualTo(key);
        });
    }

    private static SupplierDto supplier(String name, String country, String currency, String tin) {
        SupplierDto dto = new SupplierDto();
        dto.setName(name);
        dto.setCountryCode(country);
        dto.setCurrencyCode(currency);
        dto.setIncoterm(Incoterm.FOB);
        dto.setTin(tin);
        dto.setPaymentTermsDays(30);
        return dto;
    }

    private static CustomerDto customer(CustomerType type, String name, String tin) {
        CustomerDto dto = new CustomerDto();
        dto.setType(type);
        dto.setName(name);
        dto.setTin(tin);
        return dto;
    }

    private Customer existingCustomer(CustomerType type, BigDecimal credit) {
        Customer c = new Customer();
        c.setId(UUID.randomUUID());
        c.setCode("CUS-WH-00009");
        c.setName("Existing");
        c.setType(type);
        c.setCreditLimit(credit);
        when(customerRepo.findWithPriceListById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private static Currency currency(String code, boolean enabled) {
        Currency c = new Currency();
        c.setId(UUID.randomUUID());
        c.setCode(code);
        c.setEnabled(enabled);
        return c;
    }

    private static PriceList priceList(String code, boolean enabled) {
        PriceList l = new PriceList();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setName(code);
        l.setEnabled(enabled);
        return l;
    }

    @Test
    void aSupplierWithOpenOrdersStaysActive() {
        Supplier supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setName("Shandong Glass");
        when(supplierRepo.findById(supplier.getId())).thenReturn(Optional.of(supplier));
        when(orderRepo.countBySupplier_IdAndStatusIn(eq(supplier.getId()), any())).thenReturn(1L);
        assertThatThrownBy(() -> suppliers.setEnabled(supplier.getId(), false)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("supplier.disable.openOrders"));
        when(orderRepo.countBySupplier_IdAndStatusIn(eq(supplier.getId()), any())).thenReturn(0L);
        assertThat(suppliers.setEnabled(supplier.getId(), false).isEnabled()).isFalse();
    }
}
