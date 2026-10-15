package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Territory;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.TerritoryRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TerritoryService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Sales territories and their Regional Sales Managers (BP 6, Res. 7; the number of territories is the
 *               client's to set, so they are records, not code): a code in capitals, unique, a name, a manager who is a
 *               user (their name copied). Deactivated, never deleted.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TerritoryService {

    private final TerritoryRepository repo;
    private final UserRepository userRepo;

    public TerritoryService(TerritoryRepository repo, UserRepository userRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
    }

    /** What a territory is given: code, name and manager. */
    public record Form(String code, String name, Long managerUserId) {
    }

    public List<Territory> all() {
        return repo.findAllByOrderByCodeAsc();
    }

    public Territory find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Territory", id));
    }

    /** Enabled users, who may manage a territory. */
    public List<User> managers() {
        return userRepo.findAll().stream().filter(User::isEnabled)
                .sorted((a, b) -> a.getFullName().compareToIgnoreCase(b.getFullName())).toList();
    }

    @Transactional
    public Territory create(Form form) {
        Territory t = new Territory();
        apply(t, form);
        return repo.save(t);
    }

    @Transactional
    public Territory update(UUID id, Form form) {
        Territory t = find(id);
        apply(t, form);
        return t;
    }

    @Transactional
    public Territory setEnabled(UUID id, boolean enabled) {
        Territory t = find(id);
        t.setEnabled(enabled);
        return t;
    }

    private void apply(Territory t, Form form) {
        String code = PartyRules.clean(form.code());
        if (code == null || !code.matches("[A-Za-z0-9-]{1,20}")) {
            throw BusinessException.onField("code", "territory.code.invalid");
        }
        code = code.toUpperCase(Locale.ROOT);
        String finalCode = code;
        if (repo.findByCode(code).filter(o -> !o.getId().equals(t.getId())).isPresent()) {
            throw BusinessException.onField("code", "territory.code.taken", finalCode);
        }
        String name = PartyRules.clean(form.name());
        if (name == null || name.length() > 100) {
            throw BusinessException.onField("name", "territory.name.required");
        }
        User manager = null;
        if (form.managerUserId() != null) {
            manager = userRepo.findById(form.managerUserId()).filter(User::isEnabled)
                    .orElseThrow(() -> BusinessException.onField("managerUserId", "territory.manager.invalid"));
        }
        t.setCode(code);
        t.setName(name);
        t.setManagerUserId(manager == null ? null : manager.getId());
        t.setManagerName(manager == null ? null : manager.getFullName());
    }
}
