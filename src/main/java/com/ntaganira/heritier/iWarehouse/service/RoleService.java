package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.RoleDto;
import com.ntaganira.heritier.iWarehouse.dto.SetChange;
import com.ntaganira.heritier.iWarehouse.entity.AppPage;
import com.ntaganira.heritier.iWarehouse.entity.Permission;
import com.ntaganira.heritier.iWarehouse.entity.Role;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AppPageRepository;
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
import java.util.stream.Stream;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : RoleService.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Roles and the pages/permissions they grant (ADM-01), ported from iVura. Roles are
 *               deactivated, not deleted. ADMIN always holds every page and permission and stays active.
 *               Grant changes go to the activity log (AUD-03); they apply at each holder's next sign-in.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class RoleService {

    static final String MODULE = "Role Management";

    private final RoleRepository roleRepo;
    private final PermissionRepository permissionRepo;
    private final AppPageRepository pageRepo;
    private final ActivityLogService activityLogService;

    public RoleService(RoleRepository roleRepo, PermissionRepository permissionRepo, AppPageRepository pageRepo,
                       ActivityLogService activityLogService) {
        this.roleRepo = roleRepo;
        this.permissionRepo = permissionRepo;
        this.pageRepo = pageRepo;
        this.activityLogService = activityLogService;
    }

    public Page<Role> findPage(String search, String status, int page, int size) {
        Specification<Role> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase() + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("code")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), term)));
            }
            if ("active".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.isTrue(root.get("enabled")));
            } else if ("inactive".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.isFalse(root.get("enabled")));
            }
            return p;
        };
        return roleRepo.findAll(spec, PageRequest.of(page, size, Sort.by("code")));
    }

    public Role findById(Long id) {
        return roleRepo.findById(id).orElseThrow(() -> new NotFoundException("Role", id));
    }

    public List<Role> findAll() {
        return roleRepo.findAllByOrderByCodeAsc();
    }

    /** Role id to number of users holding it (roles without users are absent). */
    public Map<Long, Long> userCounts() {
        Map<Long, Long> counts = new HashMap<>();
        roleRepo.countUsersPerRole().forEach(row -> counts.put((Long) row[0], (Long) row[1]));
        return counts;
    }

    /** Every page, grouped by module in menu order (role form and role detail). */
    public Map<String, List<AppPage>> pagesByModule() {
        return pageRepo.findAllByOrderBySortOrderAsc().stream()
                .collect(Collectors.groupingBy(AppPage::getModule, LinkedHashMap::new, Collectors.toList()));
    }

    /** The role's own permissions grouped by module (role detail page). */
    public static Map<String, List<Permission>> permissionsByModule(Role role) {
        return role.getPermissions().stream()
                .sorted(Comparator.comparing(Permission::getModule).thenComparing(Permission::getCode))
                .collect(Collectors.groupingBy(Permission::getModule, LinkedHashMap::new, Collectors.toList()));
    }

    public static boolean isAdmin(Role role) {
        return UserService.ADMIN_ROLE.equals(role.getCode());
    }

    @Transactional
    public Role create(RoleDto dto) {
        String code = dto.getCode().trim().toUpperCase(Locale.ROOT);
        if (roleRepo.existsByCodeIgnoreCase(code)) {
            throw BusinessException.onField("code", "role.code.taken", code);
        }
        Set<Permission> permissions = resolvePermissions(dto.getPermissionIds());
        Set<AppPage> pages = resolvePages(dto.getPageIds());
        Role role = roleRepo.save(Role.builder()
                .name("ROLE_" + code)
                .code(code)
                .description(dto.getDescription().trim())
                .discountLimitPercent(dto.getDiscountLimitPercent())
                .permissions(permissions)
                .pages(pages)
                .build());
        logAccessChange(role,
                SetChange.of(Set.of(), permissionCodes(permissions)),
                SetChange.of(Set.of(), pageCodes(pages)));
        return role;
    }

    /** Updates the description and grants. The code never changes; ADMIN's grants never change. */
    @Transactional
    public Role update(Long id, RoleDto dto) {
        Role role = findById(id);
        role.setDescription(dto.getDescription().trim());
        role.setDiscountLimitPercent(dto.getDiscountLimitPercent());
        if (isAdmin(role)) {
            return role;
        }
        Set<Permission> permissions = resolvePermissions(dto.getPermissionIds());
        Set<AppPage> pages = resolvePages(dto.getPageIds());
        SetChange permissionChange = SetChange.of(permissionCodes(role.getPermissions()), permissionCodes(permissions));
        SetChange pageChange = SetChange.of(pageCodes(role.getPages()), pageCodes(pages));
        role.getPermissions().clear();
        role.getPermissions().addAll(permissions);
        role.getPages().clear();
        role.getPages().addAll(pages);
        logAccessChange(role, permissionChange, pageChange);
        return role;
    }

    @Transactional
    public Role setEnabled(Long id, boolean enabled) {
        Role role = findById(id);
        if (!enabled && isAdmin(role)) {
            throw BusinessException.of("role.admin.disable");
        }
        role.setEnabled(enabled);
        return role;
    }

    private void logAccessChange(Role role, SetChange permissions, SetChange pages) {
        if (permissions.isEmpty() && pages.isEmpty()) {
            return;
        }
        String changes = Stream.of(permissions.describe("permissions"), pages.describe("pages"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("; "));
        String description = "Role " + role.getCode() + ": " + changes;
        AfterCommit.run(() -> activityLogService.record(MODULE, "UPDATE_ROLE_ACCESS", description, ActivityStatus.SUCCESS));
    }

    private Set<Permission> resolvePermissions(Collection<Long> ids) {
        return ids == null || ids.isEmpty() ? new HashSet<>() : new HashSet<>(permissionRepo.findAllById(ids));
    }

    private Set<AppPage> resolvePages(Collection<Long> ids) {
        return ids == null || ids.isEmpty() ? new HashSet<>() : new HashSet<>(pageRepo.findAllById(ids));
    }

    private static Set<String> permissionCodes(Collection<Permission> permissions) {
        return permissions.stream().map(Permission::getCode).collect(Collectors.toSet());
    }

    private static Set<String> pageCodes(Collection<AppPage> pages) {
        return pages.stream().map(AppPage::getCode).collect(Collectors.toSet());
    }
}
