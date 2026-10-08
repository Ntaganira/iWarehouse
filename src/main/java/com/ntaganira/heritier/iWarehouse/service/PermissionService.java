package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.PermissionDto;
import com.ntaganira.heritier.iWarehouse.entity.Permission;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.PermissionRepository;
import com.ntaganira.heritier.iWarehouse.repository.RoleRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PermissionService.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Permission catalogue (ADM-01). Permissions come from module migrations because code
 *               checks them by code; here they are only relabelled or switched off. Switching off the
 *               ones needed to sign in and manage access is refused, so nobody can lock the system.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class PermissionService {

    /** Modules whose permissions keep the dashboard and access management reachable. */
    public static final Set<String> PROTECTED_MODULES =
            Set.of("Dashboard", "User Management", "Role Management", "Permission Management");

    private final PermissionRepository permissionRepo;
    private final RoleRepository roleRepo;

    public PermissionService(PermissionRepository permissionRepo, RoleRepository roleRepo) {
        this.permissionRepo = permissionRepo;
        this.roleRepo = roleRepo;
    }

    public Page<Permission> findPage(String search, String module, int page, int size) {
        Specification<Permission> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase() + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("name")), term),
                        cb.like(cb.lower(root.get("code")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), term)));
            }
            if (StringUtils.hasText(module)) {
                p = cb.and(p, cb.equal(root.get("module"), module.trim()));
            }
            return p;
        };
        return permissionRepo.findAll(spec, PageRequest.of(page, size, Sort.by("module").and(Sort.by("code"))));
    }

    public Permission findById(Long id) {
        return permissionRepo.findById(id).orElseThrow(() -> new NotFoundException("Permission", id));
    }

    public List<String> findModules() {
        return permissionRepo.findDistinctModules();
    }

    /** Every permission grouped by module, in code order (role form). */
    public Map<String, List<Permission>> byModule() {
        return permissionRepo.findAllByOrderByModuleAscCodeAsc().stream()
                .collect(Collectors.groupingBy(Permission::getModule, LinkedHashMap::new, Collectors.toList()));
    }

    /** Permission id to number of roles granting it. */
    public Map<Long, Long> roleCounts() {
        Map<Long, Long> counts = new HashMap<>();
        roleRepo.countRolesPerPermission().forEach(row -> counts.put((Long) row[0], (Long) row[1]));
        return counts;
    }

    public static boolean isProtected(Permission permission) {
        return PROTECTED_MODULES.contains(permission.getModule());
    }

    @Transactional
    public Permission update(Long id, PermissionDto dto) {
        Permission permission = findById(id);
        permission.setName(dto.getName().trim());
        permission.setDescription(StringUtils.hasText(dto.getDescription()) ? dto.getDescription().trim() : null);
        return permission;
    }

    @Transactional
    public Permission setEnabled(Long id, boolean enabled) {
        Permission permission = findById(id);
        if (!enabled && isProtected(permission)) {
            throw BusinessException.of("perm.protected", permission.getCode());
        }
        permission.setEnabled(enabled);
        return permission;
    }
}
