package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.CostCentreDto;
import com.ntaganira.heritier.iWarehouse.entity.CostCentre;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CostCentreRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CostCentreService.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Cost centres (F-15 A, T.05): a code in capitals, unique, a name and a manager (an active user, whose name
 *               is kept), added, renamed and deactivated by Finance (PERM_MANAGE_COST_CENTRES); never deleted, since
 *               journal lines name them.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class CostCentreService {

    private final CostCentreRepository repo;
    private final UserRepository userRepo;

    public CostCentreService(CostCentreRepository repo, UserRepository userRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
    }

    public List<CostCentre> all() {
        return repo.findAllByOrderByCodeAsc();
    }

    public CostCentre find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("CostCentre", id));
    }

    /** Active users a cost centre may be managed by, by name. */
    public List<User> users() {
        return userRepo.findAll(Sort.by("fullName")).stream().filter(User::isEnabled).toList();
    }

    public CostCentreDto formOf(CostCentre c) {
        CostCentreDto dto = new CostCentreDto();
        dto.setCode(c.getCode());
        dto.setName(c.getName());
        dto.setManagerUserId(c.getManagerUserId());
        dto.setEnabled(c.isEnabled());
        return dto;
    }

    @Transactional
    public CostCentre save(UUID id, CostCentreDto dto) {
        CostCentre c = id == null ? new CostCentre() : find(id);
        String code = dto.getCode().trim().toUpperCase(Locale.ROOT);
        repo.findByCode(code).filter(other -> !other.getId().equals(c.getId())).ifPresent(other -> {
            throw BusinessException.onField("code", "costCentre.code.taken", code);
        });
        c.setCode(code);
        c.setName(dto.getName().trim());
        if (dto.getManagerUserId() != null) {
            User manager = userRepo.findById(dto.getManagerUserId()).filter(User::isEnabled)
                    .orElseThrow(() -> BusinessException.onField("managerUserId", "costCentre.manager.inactive"));
            c.setManagerUserId(manager.getId());
            c.setManagerName(manager.getFullName() == null ? manager.getUsername() : manager.getFullName());
        } else {
            c.setManagerUserId(null);
            c.setManagerName(null);
        }
        c.setEnabled(id == null || dto.isEnabled());
        return repo.save(c);
    }
}
