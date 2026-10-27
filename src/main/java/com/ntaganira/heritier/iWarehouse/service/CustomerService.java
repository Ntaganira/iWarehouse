package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.CustomerDto;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.Territory;
import com.ntaganira.heritier.iWarehouse.entity.PriceList;
import com.ntaganira.heritier.iWarehouse.enums.CustomerType;
import com.ntaganira.heritier.iWarehouse.enums.Market;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.TerritoryRepository;
import com.ntaganira.heritier.iWarehouse.repository.PriceListRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CustomerService.java
 * - Date      : 2026. 10. 08.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Customers (MD-04). One customer per TIN. Credit limit, payment terms and price list
 *               change only for users allowed to set terms (PERM_MANAGE_CUSTOMER_TERMS); others create
 *               cash customers on the default list and cannot take credit away by turning an account
 *               into a walk-in. Walk-in customers never have credit. The default walk-in customer stays
 *               a walk-in and stays active. Codes come from DocumentNumberService (CUS-WH-00001).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository repo;
    private final PriceListRepository priceListRepo;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final TerritoryRepository territoryRepo;

    public CustomerService(CustomerRepository repo, PriceListRepository priceListRepo, DocumentNumberService numbers,
                           SettingService settingService, TerritoryRepository territoryRepo) {
        this.territoryRepo = territoryRepo;
        this.settingService = settingService;
        this.repo = repo;
        this.priceListRepo = priceListRepo;
        this.numbers = numbers;
    }

    /** Active territories, for the form. */
    public List<Territory> territories() {
        return territoryRepo.findByEnabledTrueOrderByCodeAsc();
    }

    /** A territory's code and name, for the customer's page. */
    public String territoryName(UUID id) {
        return territoryRepo.findById(id).map(t -> t.getCode() + " · " + t.getName()).orElse(null);
    }

    public Page<Customer> findPage(String search, CustomerType type, String status, int page, int size) {
        Specification<Customer> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("name")), term),
                        cb.like(cb.lower(root.get("code")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("phone"), "")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("tin"), "")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("contactName"), "")), term)));
            }
            if (type != null) {
                p = cb.and(p, cb.equal(root.get("type"), type));
            }
            if ("active".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.isTrue(root.get("enabled")));
            } else if ("inactive".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.isFalse(root.get("enabled")));
            }
            return p;
        };
        Sort sort = Sort.by(Sort.Order.desc("defaultCustomer")).and(Sort.by("name")).and(Sort.by("id"));
        return repo.findAll(spec, PageRequest.of(page, size, sort));
    }

    public Customer findById(UUID id) {
        return repo.findWithPriceListById(id).orElseThrow(() -> new NotFoundException("Customer", id));
    }

    /** Active price lists, plus the customer's own one if it has since been deactivated. */
    public List<PriceList> priceListsFor(Customer current) {
        List<PriceList> lists = new ArrayList<>(priceListRepo.findByEnabledTrueOrderByDefaultListDescNameAsc());
        if (current != null && current.getPriceList() != null && !current.getPriceList().isEnabled()) {
            lists.add(current.getPriceList());
        }
        return lists;
    }

    @Transactional
    public Customer create(CustomerDto dto, boolean canSetTerms) {
        String tin = PartyRules.clean(dto.getTin());
        checkTin(tin, null);
        checkPhone(dto, null);
        Customer customer = new Customer();
        customer.setCode(numbers.next(DocumentType.CUSTOMER));
        customer.setType(dto.getType());
        apply(customer, dto, tin);
        if (canSetTerms && !termsByApplication()) {
            applyTerms(customer, dto);
        }
        return repo.save(customer);
    }

    @Transactional
    public Customer update(UUID id, CustomerDto dto, boolean canSetTerms) {
        Customer customer = findById(id);
        if (customer.isDefaultCustomer() && dto.getType() != CustomerType.WALK_IN) {
            throw BusinessException.onField("type", "customer.default.type", customer.getCode());
        }
        boolean setsTerms = canSetTerms && !termsByApplication();
        boolean losesCredit = !dto.getType().isCreditAllowed() && hasTerms(customer);
        if (losesCredit && !setsTerms) {
            throw BusinessException.onField("type", termsByApplication() ? "customer.type.termsByApplication" : "customer.type.termsNeeded");
        }
        String tin = PartyRules.clean(dto.getTin());
        checkTin(tin, customer);
        checkPhone(dto, customer);
        customer.setType(dto.getType());
        apply(customer, dto, tin);
        if (setsTerms) {
            applyTerms(customer, dto);
        }
        return customer;
    }

    @Transactional
    public Customer setEnabled(UUID id, boolean enabled) {
        Customer customer = findById(id);
        if (!enabled && customer.isDefaultCustomer()) {
            throw BusinessException.of("customer.default.disable", customer.getCode());
        }
        customer.setEnabled(enabled);
        return customer;
    }

    private static boolean hasTerms(Customer customer) {
        return customer.getCreditLimit().signum() > 0 || customer.getPaymentTermsDays() > 0;
    }

    /** One customer per taxpayer, so a credit limit cannot be doubled with a second account. */
    private void checkTin(String tin, Customer current) {
        if (tin == null) {
            return;
        }
        repo.findByTin(tin)
                .filter(other -> current == null || !other.getId().equals(current.getId()))
                .ifPresent(other -> {
                    throw BusinessException.onField("tin", "customer.tin.taken", tin, other.getCode(), other.getName());
                });
    }

    /** Whether credit terms change only through an approved credit application (F-02 C-D, Settings). */
    public boolean termsByApplication() {
        return settingService.getBoolean(SettingKey.APPROVAL_CREDIT_APPLICATION);
    }

    /**
     * A phone already on another customer is saved only once confirmed: it may be the same buyer under two names (BP 11).
     * A phone kept as it was was confirmed when it was saved.
     */
    private void checkPhone(CustomerDto dto, Customer current) {
        String phone = PartyRules.clean(dto.getPhone());
        if (phone == null || dto.isConfirmDuplicate() || (current != null && phone.equals(current.getPhone()))) {
            return;
        }
        repo.findByPhone(phone).stream()
                .filter(other -> current == null || !other.getId().equals(current.getId()))
                .findFirst()
                .ifPresent(other -> {
                    throw BusinessException.onField("phone", "customer.phone.duplicate", other.getCode(), other.getName());
                });
    }

    private void apply(Customer customer, CustomerDto dto, String tin) {
        if (dto.getTerritoryId() != null && territoryRepo.findById(dto.getTerritoryId()).filter(Territory::isEnabled).isEmpty()
                && !dto.getTerritoryId().equals(customer.getTerritoryId())) {
            throw BusinessException.onField("territoryId", "order.territory.invalid");
        }
        customer.setTerritoryId(dto.getTerritoryId());
        customer.setMarket(dto.getMarket() == null ? Market.DOMESTIC : dto.getMarket());
        customer.setAgent(dto.isAgent() && customer.getType().isCreditAllowed());
        customer.setName(dto.getName().trim());
        customer.setTin(tin);
        customer.setPhone(PartyRules.clean(dto.getPhone()));
        customer.setEmail(PartyRules.clean(dto.getEmail()));
        customer.setContactName(PartyRules.clean(dto.getContactName()));
        customer.setAddress(PartyRules.clean(dto.getAddress()));
        customer.setNotes(PartyRules.clean(dto.getNotes()));
        customer.setRegistrationNumber(PartyRules.clean(dto.getRegistrationNumber()));
        customer.setDistrict(PartyRules.clean(dto.getDistrict()));
        customer.setCountryCode(PartyRules.clean(dto.getCountryCode()));
        customer.setBankName(PartyRules.clean(dto.getBankName()));
        customer.setBankAccount(PartyRules.clean(dto.getBankAccount()));
        customer.setMomoNumber(PartyRules.clean(dto.getMomoNumber()));
        customer.setExpectedMonthly(dto.getExpectedMonthly());
        customer.setTradeReferences(PartyRules.clean(dto.getTradeReferences()));
        customer.setSecurityOffered(PartyRules.clean(dto.getSecurityOffered()));
        if (!customer.getType().isCreditAllowed()) {
            customer.setCreditLimit(BigDecimal.ZERO); // walk-ins pay at the counter (chk_customers_walk_in_cash)
            customer.setPaymentTermsDays(0);
        }
    }

    /**
     * Sets the terms an approved credit application gave (F-02 D), with the day they were reviewed and the next review:
     * the one way terms change while Settings send them through applications. Inside the approval's transaction.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Customer applyApprovedTerms(UUID customerId, BigDecimal creditLimit, int paymentTermsDays, UUID priceListId,
                                       java.time.LocalDate reviewedOn, java.time.LocalDate nextReview) {
        Customer customer = findById(customerId);
        if (!customer.getType().isCreditAllowed() && (creditLimit.signum() > 0 || paymentTermsDays > 0)) {
            throw BusinessException.of("creditApp.walkIn", customer.getName());
        }
        customer.setCreditLimit(creditLimit);
        customer.setPaymentTermsDays(paymentTermsDays);
        customer.setPriceList(priceListId == null ? null : priceListRepo.findById(priceListId)
                .orElseThrow(() -> new NotFoundException("PriceList", priceListId)));
        customer.setCreditReviewedOn(reviewedOn);
        customer.setCreditReviewDue(nextReview);
        return customer;
    }

    private void applyTerms(Customer customer, CustomerDto dto) {
        if (customer.getType().isCreditAllowed()) {
            customer.setCreditLimit(dto.getCreditLimit());
            customer.setPaymentTermsDays(dto.getPaymentTermsDays());
        }
        PriceList list = null;
        if (dto.getPriceListId() != null) {
            list = priceListRepo.findById(dto.getPriceListId())
                    .orElseThrow(() -> BusinessException.onField("priceListId", "customer.priceList.inactive", "?"));
            boolean unchanged = customer.getPriceList() != null && customer.getPriceList().getId().equals(list.getId());
            if (!list.isEnabled() && !unchanged) {
                throw BusinessException.onField("priceListId", "customer.priceList.inactive", list.getName());
            }
        }
        customer.setPriceList(list);
    }
}
