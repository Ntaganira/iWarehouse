package com.ntaganira.heritier.iWarehouse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.entity.SyncConflict;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.enums.SyncConflictReason;
import com.ntaganira.heritier.iWarehouse.enums.SyncConflictStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.SyncConflictRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SyncConflictService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Mobile sales the server could not take as they were made (SYNC-05). The supervisor sees why and the sale
 *               as the phone sent it, settles it with the documents that fit (a counter sale, a credit note, an adjustment,
 *               the end of day) and records what was done: the conflict is REVIEWED, never deleted.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class SyncConflictService {

    private final SyncConflictRepository repo;
    private final StockUnitRepository unitRepo;
    private final CustomerRepository customerRepo;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SyncConflictService(SyncConflictRepository repo, StockUnitRepository unitRepo, CustomerRepository customerRepo, ObjectMapper mapper,
                               Clock clock) {
        this.repo = repo;
        this.unitRepo = unitRepo;
        this.customerRepo = customerRepo;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** A unit of a sale kept for the supervisor: what the phone charged for it, and the unit as it is now (null if unknown). */
    public record SentLine(UUID unitId, StockUnit unit, BigDecimal pricePerM2, String priceReason, BigDecimal amount) {
    }

    /** What the phone sold, readable (SYNC-05): the customer, each unit, the payments. */
    public record SentSale(String customer, String buyerName, String buyerTin, List<SentLine> lines,
                           List<MobileSaleService.PaymentRequest> payments, BigDecimal cashTendered) {
    }

    public Page<SyncConflict> findPage(String search, SyncConflictStatus status, SyncConflictReason reason, int page, int size) {
        Specification<SyncConflict> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                // The trip's number too: a trip's page links its open conflicts this way
                Join<SyncConflict, Trip> trip = root.join("trip", JoinType.LEFT);
                p = cb.and(p, cb.or(cb.like(cb.lower(cb.coalesce(root.get("number"), "")), term), cb.like(cb.lower(root.get("username")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("detail"), "")), term), cb.like(cb.lower(trip.get("number")), term)));
            }
            if (status != null) {
                p = cb.and(p, cb.equal(root.get("status"), status));
            }
            if (reason != null) {
                p = cb.and(p, cb.equal(root.get("reason"), reason));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt")));
    }

    public SyncConflict findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("SyncConflict", id));
    }

    /** The sale a conflict kept, read from what the phone sent; empty when that cannot be read (an INVALID sale). */
    public Optional<SentSale> sent(SyncConflict c) {
        MobileSaleService.SaleRequest req;
        try {
            req = mapper.readValue(c.getPayload(), MobileSaleService.SaleRequest.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return Optional.empty();
        }
        List<MobileSaleService.LineRequest> lines = req.lines() == null ? List.of() : req.lines().stream().filter(Objects::nonNull).toList();
        Map<UUID, StockUnit> units = unitRepo.findByIdIn(lines.stream().map(MobileSaleService.LineRequest::unitId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(StockUnit::getId, Function.identity()));
        String customer = req.customerId() == null ? null : customerRepo.findById(req.customerId()).map(Customer::getName).orElse(null);
        return Optional.of(new SentSale(customer, req.buyerName(), req.buyerTin(),
                lines.stream().map(l -> new SentLine(l.unitId(), l.unitId() == null ? null : units.get(l.unitId()), l.pricePerM2(), l.priceReason(),
                        l.amount())).toList(),
                req.payments() == null ? List.of() : req.payments().stream().filter(Objects::nonNull).toList(), req.cashTendered()));
    }

    public long openCount() {
        return repo.countByStatus(SyncConflictStatus.OPEN);
    }

    /** The sales of a trip still waiting for the supervisor. */
    public long openOfTrip(UUID tripId) {
        return repo.countByTrip_IdAndStatus(tripId, SyncConflictStatus.OPEN);
    }

    /** Records what was done about a conflict (the caller gives the note to the change log as its reason). */
    @Transactional
    public SyncConflict review(UUID id, String note) {
        SyncConflict c = repo.lockById(id).orElseThrow(() -> new NotFoundException("SyncConflict", id));
        if (!c.isOpen()) {
            throw BusinessException.of("sync.alreadyReviewed", c.getNumber() == null ? "—" : c.getNumber());
        }
        c.setReviewedAt(LocalDateTime.now(clock));
        c.setReviewedBy(AppUserPrincipal.currentUsername());
        c.setReviewNote(note.trim());
        c.setStatus(SyncConflictStatus.REVIEWED);
        return c;
    }
}
