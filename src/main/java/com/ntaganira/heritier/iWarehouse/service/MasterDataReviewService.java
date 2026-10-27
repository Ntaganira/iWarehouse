package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.DataChangeLog;
import com.ntaganira.heritier.iWarehouse.entity.MasterDataReview;
import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : MasterDataReviewService.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The monthly review of master data (Board Paper 11 and Table 9): every change made in a month to customers,
 *               suppliers, glass, price lists, prices, credit applications and price changes (from the change log), the
 *               accounts that look duplicated (customers sharing a phone, suppliers sharing a bank account) and the credit
 *               customers without their papers; a reviewer signs the month off once it has ended, with a note.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class MasterDataReviewService {

    /** The records the review covers, in the order its counts show them. */
    public static final List<String> TYPES = List.of("Customer", "Supplier", "Product", "PriceList", "PriceListItem", "ServicePrice",
            "ProcessingService", "CreditApplication", "PriceChange");

    private final DataChangeLogRepository changeRepo;
    private final MasterDataReviewRepository reviewRepo;
    private final CustomerRepository customerRepo;
    private final SupplierRepository supplierRepo;
    private final AttachmentRepository attachmentRepo;
    private final Clock clock;

    public MasterDataReviewService(DataChangeLogRepository changeRepo, MasterDataReviewRepository reviewRepo, CustomerRepository customerRepo,
                                   SupplierRepository supplierRepo, AttachmentRepository attachmentRepo, Clock clock) {
        this.changeRepo = changeRepo;
        this.reviewRepo = reviewRepo;
        this.customerRepo = customerRepo;
        this.supplierRepo = supplierRepo;
        this.attachmentRepo = attachmentRepo;
        this.clock = clock;
    }

    /** Records that share something that should be theirs alone: a phone, a bank account. */
    public record Duplicate<T>(String value, List<T> records) {
    }

    public YearMonth thisMonth() {
        return YearMonth.now(clock);
    }

    public Page<DataChangeLog> changes(YearMonth month, int page, int size) {
        return changeRepo.findByEntityTypeInAndServerTimeBetweenOrderByServerTimeDescIdDesc(TYPES, from(month), to(month),
                PageRequest.of(page, size));
    }

    public List<DataChangeLog> allChanges(YearMonth month) {
        return changeRepo.findByEntityTypeInAndServerTimeBetweenOrderByServerTimeAscIdAsc(TYPES, from(month), to(month));
    }

    /** How many changes each kind of record had in the month (kinds without any left out). */
    public Map<String, Long> counts(YearMonth month) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String type : TYPES) {
            long n = changeRepo.countByEntityTypeAndServerTimeBetween(type, from(month), to(month));
            if (n > 0) {
                counts.put(type, n);
            }
        }
        return counts;
    }

    /** Active customers sharing a phone: the same buyer under two names, or a relative (BP 11). */
    public List<Duplicate<Customer>> customersSharingPhone() {
        return group(customerRepo.findByEnabledTrueOrderByNameAsc(), Customer::getPhone);
    }

    /** Active suppliers paid into the same bank account: one of them may not be who they say. */
    public List<Duplicate<Supplier>> suppliersSharingAccount() {
        return group(supplierRepo.findAll().stream().filter(Supplier::isEnabled).sorted(Comparator.comparing(Supplier::getName)).toList(),
                s -> s.getBankAccount() == null ? null : s.getBankAccount().replaceAll("[\\s-]", ""));
    }

    /** Active customers with credit but without their registration number or any document (SDP 11, F-02). */
    public List<Customer> creditWithoutPapers() {
        return customerRepo.findByEnabledTrueOrderByNameAsc().stream()
                .filter(c -> c.getType().isCreditAllowed() && c.hasCredit())
                .filter(c -> c.getRegistrationNumber() == null
                        || attachmentRepo.countByOwnerTypeAndOwnerIdAndRemovedAtIsNull(AttachmentOwner.CUSTOMER, c.getId()) == 0)
                .toList();
    }

    public Optional<MasterDataReview> reviewOf(YearMonth month) {
        return reviewRepo.findByPeriodStart(month.atDay(1));
    }

    public Page<MasterDataReview> reviews(int page, int size) {
        return reviewRepo.findAllByOrderByPeriodStartDesc(PageRequest.of(page, size));
    }

    /** Signs an ended month off, once, with the number of its changes and a note. */
    @Transactional
    public MasterDataReview review(YearMonth month, String note) {
        if (!month.isBefore(thisMonth())) {
            throw BusinessException.of("masterData.review.notEnded", month.toString());
        }
        if (reviewOf(month).isPresent()) {
            throw BusinessException.of("masterData.review.done", month.toString());
        }
        String clean = PartyRules.clean(note);
        if (clean != null && clean.length() > 500) {
            throw BusinessException.onField("note", "masterData.review.noteSize");
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        MasterDataReview review = new MasterDataReview();
        review.setPeriodStart(month.atDay(1));
        review.setChanges((int) counts(month).values().stream().mapToLong(Long::longValue).sum());
        review.setNote(clean);
        review.setReviewedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        review.setReviewedById(user.map(AppUserPrincipal::getId).orElse(null));
        review.setReviewedAt(LocalDateTime.now(clock));
        return reviewRepo.save(review);
    }

    private static <T> List<Duplicate<T>> group(List<T> records, java.util.function.Function<T, String> key) {
        Map<String, List<T>> byKey = records.stream().filter(r -> key.apply(r) != null)
                .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
        return byKey.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .map(e -> new Duplicate<>(e.getKey(), e.getValue())).toList();
    }

    private static LocalDateTime from(YearMonth month) {
        return month.atDay(1).atStartOfDay();
    }

    private static LocalDateTime to(YearMonth month) {
        return month.atEndOfMonth().plusDays(1).atStartOfDay().minusNanos(1);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
