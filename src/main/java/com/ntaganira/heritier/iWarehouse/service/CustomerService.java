package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.CustomerDto;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.PriceList;
import com.ntaganira.heritier.iWarehouse.enums.CustomerType;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
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
 * - User      : Hntaganira
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

    public CustomerService(CustomerRepository repo, PriceListRepository priceListRepo, DocumentNumberService numbers) {
        this.repo = repo;
        this.priceListRepo = priceListRepo;
        this.numbers = numbers;
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
        Customer customer = new Customer();
        customer.setCode(numbers.next(DocumentType.CUSTOMER));
        customer.setType(dto.getType());
        apply(customer, dto, tin);
        if (canSetTerms) {
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
        boolean losesCredit = !dto.getType().isCreditAllowed() && hasTerms(customer);
        if (losesCredit && !canSetTerms) {
            throw BusinessException.onField("type", "customer.type.termsNeeded");
        }
        String tin = PartyRules.clean(dto.getTin());
        checkTin(tin, customer);
        customer.setType(dto.getType());
        apply(customer, dto, tin);
        if (canSetTerms) {
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

    private static void apply(Customer customer, CustomerDto dto, String tin) {
        customer.setName(dto.getName().trim());
        customer.setTin(tin);
        customer.setPhone(PartyRules.clean(dto.getPhone()));
        customer.setEmail(PartyRules.clean(dto.getEmail()));
        customer.setContactName(PartyRules.clean(dto.getContactName()));
        customer.setAddress(PartyRules.clean(dto.getAddress()));
        customer.setNotes(PartyRules.clean(dto.getNotes()));
        if (!customer.getType().isCreditAllowed()) {
            customer.setCreditLimit(BigDecimal.ZERO); // walk-ins pay at the counter (chk_customers_walk_in_cash)
            customer.setPaymentTermsDays(0);
        }
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
