package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityDecision;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.PriceChangeStatus;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PriceChangeService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Prices approved before they apply (SDP 5): with Settings asking for it, the prices typed on a price list
 *               become a price change (PCH, its lines each price from and to, append-only) that another person approves
 *               within the Delegation of Authority (the Managing Director by default), which writes them on the list, or
 *               rejects; whoever asked may withdraw it. One change waits on a list at a time; a change whose old prices no
 *               longer hold is refused, to be asked again.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class PriceChangeService {

    private final PriceChangeRepository repo;
    private final PriceChangeLineRepository lineRepo;
    private final PriceListService priceListService;
    private final PriceListItemRepository itemRepo;
    private final ServicePriceRepository servicePriceRepo;
    private final ProductRepository productRepo;
    private final ProcessingServiceRepository serviceRepo;
    private final DocumentNumberService numbers;
    private final DelegationOfAuthority authority;
    private final SettingService settingService;
    private final Notifier notifier;
    private final Clock clock;

    public PriceChangeService(PriceChangeRepository repo, PriceChangeLineRepository lineRepo, PriceListService priceListService,
                              PriceListItemRepository itemRepo, ServicePriceRepository servicePriceRepo, ProductRepository productRepo,
                              ProcessingServiceRepository serviceRepo, DocumentNumberService numbers, DelegationOfAuthority authority,
                              SettingService settingService, Notifier notifier, Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.priceListService = priceListService;
        this.itemRepo = itemRepo;
        this.servicePriceRepo = servicePriceRepo;
        this.productRepo = productRepo;
        this.serviceRepo = serviceRepo;
        this.numbers = numbers;
        this.authority = authority;
        this.settingService = settingService;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Whether prices change only once approved (Settings). */
    public boolean approvalRequired() {
        return settingService.getBoolean(SettingKey.APPROVAL_PRICE_CHANGE);
    }

    // ---------------------------------------------------------------- reading

    public Page<PriceChange> findPage(String status, String search, int page, int size) {
        Specification<PriceChange> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (StringUtils.hasText(status)) {
                try {
                    p.add(cb.equal(root.get("status"), PriceChangeStatus.valueOf(status)));
                } catch (IllegalArgumentException ignored) {
                    // an unknown status filters nothing
                }
            }
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("priceList").get("name")), term),
                        cb.like(cb.lower(root.get("reason")), term), cb.like(cb.lower(root.get("requestedBy")), term)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("number"))));
    }

    public PriceChange findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("PriceChange", id));
    }

    public List<PriceChangeLine> lines(UUID changeId) {
        return lineRepo.findByChangeIdOrderByLineNo(changeId);
    }

    public Optional<PriceChange> waitingOn(UUID listId) {
        return repo.findByPriceList_IdAndStatus(listId, PriceChangeStatus.PENDING);
    }

    public long waitingCount() {
        return repo.countByStatus(PriceChangeStatus.PENDING);
    }

    public DelegationOfAuthority.Check authorityOf(PriceChange change) {
        return authority.check(AuthorityDecision.PRICE_CHANGE, BigDecimal.ZERO);
    }

    public static boolean isRequester(PriceChange change) {
        return change.getRequestedById() != null && AppUserPrincipal.current().map(u -> u.getId().equals(change.getRequestedById())).orElse(false);
    }

    // ---------------------------------------------------------------- deciding

    /**
     * Asks for the prices typed on a list (a value adds or changes a price, null clears it), with the reason; only real
     * changes become lines. Null when nothing changes.
     */
    @Transactional
    public PriceChange request(UUID listId, Map<UUID, BigDecimal> productPrices, Map<UUID, BigDecimal> servicePrices, String reason) {
        PriceList list = priceListService.findById(listId);
        waitingOn(listId).ifPresent(w -> {
            throw BusinessException.of("priceChange.waiting", w.getNumber(), list.getName());
        });
        String why = PartyRules.clean(reason);
        if (why == null || why.length() > 255) {
            throw BusinessException.onField("reason", why == null ? "priceChange.reason.required" : "priceChange.reason.size");
        }
        Map<UUID, BigDecimal> productNow = itemRepo.findByPriceList_Id(listId).stream()
                .filter(i -> i.getPricePerM2() != null)
                .collect(Collectors.toMap(i -> i.getProduct().getId(), PriceListItem::getPricePerM2));
        Map<UUID, BigDecimal> serviceNow = servicePriceRepo.findByPriceList_Id(listId).stream()
                .filter(s -> s.getPrice() != null)
                .collect(Collectors.toMap(s -> s.getService().getId(), ServicePrice::getPrice));
        List<PriceChangeLine> lines = new ArrayList<>();
        productPrices.forEach((productId, price) -> {
            BigDecimal old = productNow.get(productId);
            if (differs(old, price)) {
                PriceChangeLine line = new PriceChangeLine();
                line.setProduct(productRepo.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId)));
                line.setOldPrice(old);
                line.setNewPrice(price);
                lines.add(line);
            }
        });
        servicePrices.forEach((serviceId, price) -> {
            BigDecimal old = serviceNow.get(serviceId);
            if (differs(old, price)) {
                PriceChangeLine line = new PriceChangeLine();
                line.setService(serviceRepo.findById(serviceId).orElseThrow(() -> new NotFoundException("ProcessingService", serviceId)));
                line.setOldPrice(old);
                line.setNewPrice(price);
                lines.add(line);
            }
        });
        if (lines.isEmpty()) {
            return null;
        }
        lines.sort(Comparator.comparing((PriceChangeLine l) -> l.isGlass() ? 0 : 1)
                .thenComparing(l -> l.isGlass() ? l.getProduct().getCode() : l.getService().getCode()));
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        PriceChange change = new PriceChange();
        change.setNumber(numbers.next(DocumentType.PRICE_CHANGE));
        change.setPriceList(list);
        change.setReason(why);
        change.setAuthorityLevel(authority.required(AuthorityDecision.PRICE_CHANGE, BigDecimal.ZERO));
        change.setStatus(PriceChangeStatus.PENDING);
        change.setRequestedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        change.setRequestedById(user.map(AppUserPrincipal::getId).orElse(null));
        change.setRequestedAt(LocalDateTime.now(clock));
        repo.save(change);
        int no = 1;
        for (PriceChangeLine line : lines) {
            line.setChangeId(change.getId());
            line.setLineNo(no++);
            lineRepo.save(line);
        }
        notifier.holders("APPROVE_PRICE_CHANGE", change.getRequestedById(), NotificationKind.APPROVAL, "notify.priceChange.waiting",
                "notify.priceChange.waitingText", "/price-lists/changes/" + change.getId(), change.getNumber(), list.getName(),
                String.valueOf(lines.size()));
        return change;
    }

    /**
     * Another person approves it within the Delegation of Authority: its prices are written on the list, provided each old
     * price still holds (otherwise it is refused and asked again).
     */
    @Transactional
    public PriceChange approve(UUID id, String note) {
        PriceChange change = lock(id);
        if (!change.isPending()) {
            throw BusinessException.of("priceChange.notPending", change.getNumber());
        }
        if (isRequester(change)) {
            throw BusinessException.of("priceChange.ownApproval", change.getNumber());
        }
        String clean = PartyRules.clean(note);
        authority.require(AuthorityDecision.PRICE_CHANGE, BigDecimal.ZERO, clean);
        UUID listId = change.getPriceList().getId();
        List<PriceChangeLine> lines = lines(id);
        Map<UUID, BigDecimal> productNow = itemRepo.findByPriceList_Id(listId).stream().filter(i -> i.getPricePerM2() != null)
                .collect(Collectors.toMap(i -> i.getProduct().getId(), PriceListItem::getPricePerM2));
        Map<UUID, BigDecimal> serviceNow = servicePriceRepo.findByPriceList_Id(listId).stream().filter(s -> s.getPrice() != null)
                .collect(Collectors.toMap(s -> s.getService().getId(), ServicePrice::getPrice));
        Map<UUID, BigDecimal> products = new LinkedHashMap<>();
        Map<UUID, BigDecimal> services = new LinkedHashMap<>();
        for (PriceChangeLine line : lines) {
            BigDecimal now = line.isGlass() ? productNow.get(line.getProduct().getId()) : serviceNow.get(line.getService().getId());
            if (differs(now, line.getOldPrice())) {
                throw BusinessException.of("priceChange.stale", change.getNumber(), line.isGlass() ? line.getProduct().getCode() : line.getService().getCode());
            }
            (line.isGlass() ? products : services).put(line.isGlass() ? line.getProduct().getId() : line.getService().getId(), line.getNewPrice());
        }
        priceListService.applyApprovedPrices(listId, products, services);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        change.setDecidedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        change.setDecidedById(user.map(AppUserPrincipal::getId).orElse(null));
        change.setDecidedAt(LocalDateTime.now(clock));
        change.setDecisionNote(clean);
        change.setStatus(PriceChangeStatus.APPROVED);
        notifier.user(change.getRequestedById(), NotificationKind.DECISION, "notify.priceChange.approved", "notify.decidedBy",
                "/price-lists/changes/" + change.getId(), change.getNumber(), change.getDecidedBy());
        return change;
    }

    /** Another person rejects it, with the reason: the list keeps its prices. */
    @Transactional
    public PriceChange reject(UUID id, String reason) {
        PriceChange change = lock(id);
        if (!change.isPending()) {
            throw BusinessException.of("priceChange.notPending", change.getNumber());
        }
        if (isRequester(change)) {
            throw BusinessException.of("priceChange.ownReject", change.getNumber());
        }
        close(change, PriceChangeStatus.REJECTED, reason);
        notifier.user(change.getRequestedById(), NotificationKind.DECISION, "notify.priceChange.rejected", "notify.decidedBy",
                "/price-lists/changes/" + change.getId(), change.getNumber(), change.getDecidedBy());
        return change;
    }

    /** Whoever asked takes it back, with a reason. */
    @Transactional
    public PriceChange withdraw(UUID id, String reason) {
        PriceChange change = lock(id);
        if (!change.isPending()) {
            throw BusinessException.of("priceChange.notPending", change.getNumber());
        }
        if (!isRequester(change)) {
            throw BusinessException.of("priceChange.notYours", change.getNumber());
        }
        close(change, PriceChangeStatus.WITHDRAWN, reason);
        return change;
    }

    private void close(PriceChange change, PriceChangeStatus status, String reason) {
        String why = PartyRules.clean(reason);
        if (why == null || why.length() > 255) {
            throw BusinessException.of("po.reason.required");
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        change.setDecidedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        change.setDecidedById(user.map(AppUserPrincipal::getId).orElse(null));
        change.setDecidedAt(LocalDateTime.now(clock));
        change.setDecisionNote(why);
        change.setStatus(status);
    }

    private static boolean differs(BigDecimal a, BigDecimal b) {
        return a == null ? b != null : b == null || a.compareTo(b) != 0;
    }

    private PriceChange lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("PriceChange", id));
        return findDetailed(id);
    }
}
