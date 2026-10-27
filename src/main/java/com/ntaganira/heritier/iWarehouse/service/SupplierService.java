package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.Countries;
import com.ntaganira.heritier.iWarehouse.dto.SupplierDto;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.SupplierApprovalStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AttachmentRepository;
import com.ntaganira.heritier.iWarehouse.repository.CurrencyRepository;
import com.ntaganira.heritier.iWarehouse.repository.PurchaseOrderRepository;
import com.ntaganira.heritier.iWarehouse.repository.SupplierRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SupplierService.java
 * - Date      : 2026. 10. 08.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Suppliers (MD-05). Names are unique (ignoring case). A new supplier, or a change of
 *               currency, needs an active currency; a Rwandan supplier's TIN has 9 digits. Codes come
 *               from DocumentNumberService (SUP-WH-0001).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class SupplierService {

    private final SupplierRepository repo;
    private final CurrencyRepository currencyRepo;
    private final DocumentNumberService numbers;
    private final Countries countries;
    private final PurchaseOrderRepository orderRepo;
    private final SettingService settingService;
    private final AttachmentRepository attachmentRepo;
    private final Notifier notifier;
    private final Clock clock;

    public SupplierService(SupplierRepository repo, CurrencyRepository currencyRepo, DocumentNumberService numbers,
                           Countries countries, PurchaseOrderRepository orderRepo, SettingService settingService,
                           AttachmentRepository attachmentRepo, Notifier notifier, Clock clock) {
        this.settingService = settingService;
        this.attachmentRepo = attachmentRepo;
        this.notifier = notifier;
        this.clock = clock;
        this.repo = repo;
        this.currencyRepo = currencyRepo;
        this.numbers = numbers;
        this.countries = countries;
        this.orderRepo = orderRepo;
    }

    public Page<Supplier> findPage(String search, String country, String currency, String status, int page, int size) {
        Specification<Supplier> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("name")), term),
                        cb.like(cb.lower(root.get("code")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("contactName"), "")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("phone"), "")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("email"), "")), term)));
            }
            if (StringUtils.hasText(country)) {
                p = cb.and(p, cb.equal(root.get("countryCode"), country));
            }
            if (StringUtils.hasText(currency)) {
                p = cb.and(p, cb.equal(root.get("currencyCode"), currency));
            }
            if ("pending".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.equal(root.get("approvalStatus"), SupplierApprovalStatus.PENDING));
            } else if ("active".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.isTrue(root.get("enabled")));
            } else if ("inactive".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.isFalse(root.get("enabled")));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by("name").and(Sort.by("id"))));
    }

    public List<String> countryCodes() {
        return repo.findCountryCodes();
    }

    public List<String> currencyCodes() {
        return repo.findCurrencyCodes();
    }

    public Supplier findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Supplier", id));
    }

    /** Active currencies, plus the supplier's own one if it has since been deactivated. */
    public List<Currency> currenciesFor(Supplier current) {
        return currencyRepo.findAllByOrderByBaseCurrencyDescEnabledDescCodeAsc().stream()
                .filter(c -> c.isEnabled() || (current != null && c.getCode().equals(current.getCurrencyCode())))
                .toList();
    }

    @Transactional
    public Supplier create(SupplierDto dto) {
        check(dto, null);
        Supplier supplier = new Supplier();
        supplier.setCode(numbers.next(DocumentType.SUPPLIER));
        apply(supplier, dto);
        // CMP 08: a new supplier waits for another person's approval before it is ordered from or paid
        supplier.setApprovalStatus(onboarding() ? SupplierApprovalStatus.PENDING : SupplierApprovalStatus.APPROVED);
        repo.save(supplier);
        if (!supplier.isApproved()) {
            tell(supplier);
        }
        return supplier;
    }

    @Transactional
    public Supplier update(UUID id, SupplierDto dto) {
        Supplier supplier = findById(id);
        check(dto, supplier);
        boolean bankChanged = !Objects.equals(supplier.getBankName(), PartyRules.clean(dto.getBankName()))
                || !Objects.equals(supplier.getBankAccount(), PartyRules.clean(dto.getBankAccount()))
                || !Objects.equals(supplier.getBankSwift(), PartyRules.clean(dto.getBankSwift()));
        apply(supplier, dto);
        // New bank details on an approved supplier, or a rejected one put right, wait for approval again (CMP 08)
        if (onboarding() && ((supplier.isApproved() && bankChanged) || supplier.getApprovalStatus() == SupplierApprovalStatus.REJECTED)) {
            supplier.setApprovalStatus(SupplierApprovalStatus.PENDING);
            supplier.setApprovedBy(null);
            supplier.setApprovedById(null);
            supplier.setApprovedAt(null);
            supplier.setApprovalNote(null);
            tell(supplier);
        }
        return supplier;
    }

    /** Whether new suppliers, and new bank details, wait for approval (Settings). */
    public boolean onboarding() {
        return settingService.getBoolean(SettingKey.APPROVAL_SUPPLIER_ONBOARDING);
    }

    public int referencesRequired() {
        return settingService.getInt(SettingKey.SUPPLIER_REFERENCES_REQUIRED);
    }

    public long pendingCount() {
        return repo.countByApprovalStatus(SupplierApprovalStatus.PENDING);
    }

    /** Who last entered or changed the supplier's details: they do not approve them. */
    public static boolean isLastEditor(Supplier supplier) {
        String me = AppUserPrincipal.currentUsername();
        String last = supplier.getUpdatedBy() != null ? supplier.getUpdatedBy() : supplier.getCreatedBy();
        return me != null && me.equals(last);
    }

    /** What the supplier still lacks to be approved (message keys), empty when complete (CMP 08). */
    public List<String> missingForApproval(Supplier supplier) {
        List<String> missing = new ArrayList<>();
        if (supplier.getRegistrationNumber() == null) {
            missing.add("supplier.missing.registration");
        }
        if (supplier.getTin() == null && PartyRules.RWANDA.equals(supplier.getCountryCode())) {
            missing.add("supplier.missing.tin");
        }
        if (supplier.getBankName() == null || supplier.getBankAccount() == null) {
            missing.add("supplier.missing.bank");
        }
        if (supplier.getReferenceList().size() < settingService.getInt(SettingKey.SUPPLIER_REFERENCES_REQUIRED)) {
            missing.add("supplier.missing.references");
        }
        if (attachmentRepo.countByOwnerTypeAndOwnerIdAndRemovedAtIsNull(AttachmentOwner.SUPPLIER, supplier.getId()) == 0) {
            missing.add("supplier.missing.documents");
        }
        return missing;
    }

    /** Another person approves a supplier waiting, once its papers are complete: it can be ordered from and paid. */
    @Transactional
    public Supplier approve(UUID id, String note) {
        Supplier supplier = lock(id);
        if (supplier.getApprovalStatus() != SupplierApprovalStatus.PENDING) {
            throw BusinessException.of("supplier.approve.notPending", supplier.getName());
        }
        if (isLastEditor(supplier)) {
            throw BusinessException.of("supplier.approve.own", supplier.getName());
        }
        List<String> missing = missingForApproval(supplier);
        if (!missing.isEmpty()) {
            throw BusinessException.of(missing.get(0), supplier.getName(), settingService.getInt(SettingKey.SUPPLIER_REFERENCES_REQUIRED));
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        supplier.setApprovedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        supplier.setApprovedById(user.map(AppUserPrincipal::getId).orElse(null));
        supplier.setApprovedAt(LocalDateTime.now(clock));
        supplier.setApprovalNote(PartyRules.clean(note));
        supplier.setApprovalStatus(SupplierApprovalStatus.APPROVED);
        return supplier;
    }

    /** Another person rejects a supplier waiting, with the reason; correcting its details sends it for approval again. */
    @Transactional
    public Supplier reject(UUID id, String reason) {
        Supplier supplier = lock(id);
        if (supplier.getApprovalStatus() != SupplierApprovalStatus.PENDING) {
            throw BusinessException.of("supplier.approve.notPending", supplier.getName());
        }
        if (isLastEditor(supplier)) {
            throw BusinessException.of("supplier.approve.own", supplier.getName());
        }
        String why = PartyRules.clean(reason);
        if (why == null || why.length() > 255) {
            throw BusinessException.of("po.reason.required");
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        supplier.setApprovedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        supplier.setApprovedById(user.map(AppUserPrincipal::getId).orElse(null));
        supplier.setApprovedAt(LocalDateTime.now(clock));
        supplier.setApprovalNote(why);
        supplier.setApprovalStatus(SupplierApprovalStatus.REJECTED);
        return supplier;
    }

    /** Only approved suppliers are ordered from or paid (CMP 08). */
    public static void requireApproved(Supplier supplier, String field) {
        if (!supplier.isApproved()) {
            throw field == null ? BusinessException.of("supplier.notApproved", supplier.getName())
                    : BusinessException.onField(field, "supplier.notApproved", supplier.getName());
        }
    }

    private void tell(Supplier supplier) {
        notifier.holders("APPROVE_SUPPLIER", AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null), NotificationKind.APPROVAL,
                "notify.supplier.waiting", "notify.supplier.waitingText", "/suppliers/" + supplier.getId(), supplier.getName(),
                supplier.getCode());
    }

    private Supplier lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("Supplier", id));
        return findById(id);
    }

    @Transactional
    public Supplier setEnabled(UUID id, boolean enabled) {
        Supplier supplier = findById(id);
        if (!enabled) {
            long open = orderRepo.countBySupplier_IdAndStatusIn(id, PurchaseOrderService.OPEN);
            if (open > 0) {
                throw BusinessException.of("supplier.disable.openOrders", supplier.getName(), open);
            }
        }
        supplier.setEnabled(enabled);
        return supplier;
    }

    private void check(SupplierDto dto, Supplier current) {
        String name = dto.getName().trim();
        boolean nameTaken = current == null ? repo.existsByNameIgnoreCase(name) : repo.existsByNameIgnoreCaseAndIdNot(name, current.getId());
        if (nameTaken) {
            throw BusinessException.onField("name", "supplier.name.taken", name);
        }
        if (!countries.isValid(dto.getCountryCode())) {
            throw BusinessException.onField("countryCode", "supplier.country.required");
        }
        Currency currency = currencyRepo.findByCode(dto.getCurrencyCode())
                .orElseThrow(() -> BusinessException.onField("currencyCode", "supplier.currency.required"));
        boolean sameCurrency = current != null && current.getCurrencyCode().equals(currency.getCode());
        if (!currency.isEnabled() && !sameCurrency) {
            throw BusinessException.onField("currencyCode", "supplier.currency.inactive", currency.getCode());
        }
        String tin = PartyRules.normalizeTin(dto.getTin());
        if (tin != null && PartyRules.RWANDA.equals(dto.getCountryCode()) && !PartyRules.isRwandaTin(tin)) {
            throw BusinessException.onField("tin", "supplier.tin.rwanda");
        }
    }

    private static void apply(Supplier supplier, SupplierDto dto) {
        supplier.setName(dto.getName().trim());
        supplier.setCountryCode(dto.getCountryCode());
        supplier.setCurrencyCode(dto.getCurrencyCode());
        supplier.setIncoterm(dto.getIncoterm());
        supplier.setTin(PartyRules.normalizeTin(dto.getTin()));
        supplier.setPaymentTermsDays(dto.getPaymentTermsDays());
        supplier.setContactName(PartyRules.clean(dto.getContactName()));
        supplier.setPhone(PartyRules.clean(dto.getPhone()));
        supplier.setEmail(PartyRules.clean(dto.getEmail()));
        supplier.setAddress(PartyRules.clean(dto.getAddress()));
        supplier.setNotes(PartyRules.clean(dto.getNotes()));
        supplier.setRegistrationNumber(PartyRules.clean(dto.getRegistrationNumber()));
        supplier.setBankName(PartyRules.clean(dto.getBankName()));
        supplier.setBankAccount(PartyRules.clean(dto.getBankAccount()));
        supplier.setBankSwift(PartyRules.clean(dto.getBankSwift()));
        supplier.setTradeReferences(PartyRules.clean(dto.getTradeReferences()));
    }
}
