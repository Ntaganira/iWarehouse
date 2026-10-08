package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.FieldChange;
import com.ntaganira.heritier.iWarehouse.entity.DataChangeLog;
import com.ntaganira.heritier.iWarehouse.enums.ChangeOperation;
import com.ntaganira.heritier.iWarehouse.repository.DataChangeLogRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/** Read side of the data change log: filtered lists, record history and before/after diffs (AUD-10, AUD-11). */
@Service
@Transactional(readOnly = true)
public class DataChangeService {

    private final DataChangeLogRepository repo;

    public DataChangeService(DataChangeLogRepository repo) {
        this.repo = repo;
    }

    public Page<DataChangeLog> findPage(String entityType, String entityId, String operation, String username,
                                        LocalDate from, LocalDate to, int page, int size) {
        Specification<DataChangeLog> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(entityType)) {
                p = cb.and(p, cb.equal(root.get("entityType"), entityType.trim()));
            }
            if (StringUtils.hasText(entityId)) {
                String term = entityId.trim();
                p = cb.and(p, cb.or(cb.equal(root.get("entityId"), term),
                        cb.equal(root.get("entityRef"), term)));
            }
            if (StringUtils.hasText(operation)) {
                try {
                    p = cb.and(p, cb.equal(root.get("operation"), ChangeOperation.valueOf(operation.trim())));
                } catch (IllegalArgumentException ignored) {
                    // unknown operation filter: ignore it
                }
            }
            if (StringUtils.hasText(username)) {
                p = cb.and(p, cb.equal(root.get("username"), username.trim()));
            }
            if (from != null) {
                p = cb.and(p, cb.greaterThanOrEqualTo(root.get("serverTime"), from.atStartOfDay()));
            }
            if (to != null) {
                p = cb.and(p, cb.lessThanOrEqualTo(root.get("serverTime"), to.atTime(LocalTime.MAX)));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "serverTime", "id")));
    }

    public Optional<DataChangeLog> findById(Long id) {
        return repo.findById(id);
    }

    /** History of one record, newest first (the History tab on detail pages). */
    public Page<DataChangeLog> history(String entityType, String entityId, int page, int size) {
        return repo.findByEntityTypeAndEntityIdOrderByServerTimeDesc(entityType, entityId, PageRequest.of(page, size));
    }

    /** History of every record of the given types, newest first (e.g. the Settings History tab). */
    public Page<DataChangeLog> historyOfTypes(Collection<String> entityTypes, int page, int size) {
        return repo.findByEntityTypeInOrderByServerTimeDescIdDesc(entityTypes, PageRequest.of(page, size));
    }

    /** History of a group of records (e.g. a price list and its price rows), newest first. */
    public Page<DataChangeLog> historyOf(Collection<String> entityTypes, Collection<String> entityIds, int page, int size) {
        if (entityIds.isEmpty()) {
            return Page.empty(PageRequest.of(page, size));
        }
        return repo.findByEntityTypeInAndEntityIdInOrderByServerTimeDescIdDesc(entityTypes, entityIds, PageRequest.of(page, size));
    }

    /**
     * History of a record and its child records, newest first, deleted children included (e.g. a
     * purchase order and its lines: "PurchaseOrderLine" whose snapshot field "purchaseOrder" is the id).
     */
    public Page<DataChangeLog> historyWithChildren(String entityType, String entityId, String childType,
                                                   String parentField, int page, int size) {
        return historyWithChildren(entityType, entityId, List.of(childType), parentField, page, size);
    }

    /** The same with several kinds of children that all name the parent in parentField (e.g. a shipment's bills and receipts). */
    public Page<DataChangeLog> historyWithChildren(String entityType, String entityId, Collection<String> childTypes,
                                                   String parentField, int page, int size) {
        return repo.findWithChildren(entityType, entityId, childTypes, parentField, PageRequest.of(page, size));
    }

    /** All changes made by the same request (e.g. one sale touching invoice, stock units and journal). */
    public List<DataChangeLog> sameRequest(String requestId) {
        return StringUtils.hasText(requestId) ? repo.findByRequestIdOrderByIdAsc(requestId) : List.of();
    }

    public List<String> entityTypes() {
        return repo.findDistinctEntityTypes();
    }

    /** Field-by-field before/after rows, changed fields first, then alphabetical. */
    public static List<FieldChange> fieldChanges(DataChangeLog log) {
        Map<String, Object> before = log.getBeforeData() == null ? Map.of() : log.getBeforeData();
        Map<String, Object> after = log.getAfterData() == null ? Map.of() : log.getAfterData();
        Set<String> changed = log.getChangedFields() == null ? Set.of() : Set.of(log.getChangedFields());
        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<FieldChange> rows = new ArrayList<>();
        for (String key : keys) {
            rows.add(new FieldChange(key, before.get(key), after.get(key), changed.contains(key)));
        }
        rows.sort(Comparator.comparing((FieldChange f) -> !f.changed()).thenComparing(FieldChange::field));
        return rows;
    }
}
