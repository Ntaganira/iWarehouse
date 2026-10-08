package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.RoleDto;
import com.ntaganira.heritier.iWarehouse.entity.AppPage;
import com.ntaganira.heritier.iWarehouse.entity.Permission;
import com.ntaganira.heritier.iWarehouse.entity.Role;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.AppPageRepository;
import com.ntaganira.heritier.iWarehouse.repository.PermissionRepository;
import com.ntaganira.heritier.iWarehouse.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** ADMIN role protection, grant logging and protected permissions. No database. */
class RoleAndPermissionRulesTest {

    private RoleRepository roleRepo;
    private PermissionRepository permissionRepo;
    private AppPageRepository pageRepo;
    private ActivityLogService activityLog;
    private RoleService roleService;
    private PermissionService permissionService;

    private final Permission viewUser = permission(10L, "VIEW_USER", "User Management");
    private final Permission createPo = permission(11L, "CREATE_PO", "Procurement");
    private final AppPage stock = AppPage.builder().id(20L).code("STOCK").name("Inventory").module("Inventory").path("/stock").build();

    @BeforeEach
    void setUp() {
        roleRepo = mock(RoleRepository.class);
        permissionRepo = mock(PermissionRepository.class);
        pageRepo = mock(AppPageRepository.class);
        activityLog = mock(ActivityLogService.class);
        roleService = new RoleService(roleRepo, permissionRepo, pageRepo, activityLog);
        permissionService = new PermissionService(permissionRepo, roleRepo);
    }

    @Test
    void adminRoleCannotBeDeactivated() {
        when(roleRepo.findById(1L)).thenReturn(Optional.of(role(1L, "ADMIN", viewUser)));

        assertThatThrownBy(() -> roleService.setEnabled(1L, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("role.admin.disable"));
    }

    @Test
    void adminGrantsNeverChange() {
        Role admin = role(1L, "ADMIN", viewUser);
        when(roleRepo.findById(1L)).thenReturn(Optional.of(admin));

        roleService.update(1L, roleDto("New description", Set.of(), Set.of()));

        assertThat(admin.getDescription()).isEqualTo("New description");
        assertThat(admin.getPermissions()).containsExactly(viewUser);
        verifyNoInteractions(activityLog);
    }

    @Test
    void grantChangesAreLogged() {
        Role cashier = role(2L, "CASHIER", viewUser);
        when(roleRepo.findById(2L)).thenReturn(Optional.of(cashier));
        when(permissionRepo.findAllById(Set.of(11L))).thenReturn(List.of(createPo));
        when(pageRepo.findAllById(Set.of(20L))).thenReturn(List.of(stock));

        roleService.update(2L, roleDto("Counter sales", Set.of(11L), Set.of(20L)));

        assertThat(cashier.getPermissions()).containsExactly(createPo);
        verify(activityLog).record(eq(RoleService.MODULE), eq("UPDATE_ROLE_ACCESS"),
                eq("Role CASHIER: permissions added [CREATE_PO], removed [VIEW_USER]; pages added [STOCK]"),
                eq(ActivityStatus.SUCCESS));
    }

    @Test
    void duplicateRoleCodeIsReportedOnTheField() {
        when(roleRepo.existsByCodeIgnoreCase("CASHIER")).thenReturn(true);
        RoleDto dto = roleDto("Counter", Set.of(), Set.of());
        dto.setCode("cashier");

        assertThatThrownBy(() -> roleService.create(dto))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getField()).isEqualTo("code"));
    }

    @Test
    void accessManagementPermissionsStayOn() {
        when(permissionRepo.findById(10L)).thenReturn(Optional.of(viewUser));
        when(permissionRepo.findById(11L)).thenReturn(Optional.of(createPo));

        assertThatThrownBy(() -> permissionService.setEnabled(10L, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("perm.protected"));
        assertThat(permissionService.setEnabled(11L, false).isEnabled()).isFalse();
    }

    private static Permission permission(Long id, String code, String module) {
        return Permission.builder().id(id).code(code).name(code).module(module).action("VIEW").build();
    }

    private static Role role(Long id, String code, Permission... permissions) {
        return Role.builder().id(id).code(code).name("ROLE_" + code).description(code)
                .permissions(new HashSet<>(Set.of(permissions))).pages(new HashSet<>()).build();
    }

    private static RoleDto roleDto(String description, Set<Long> permissionIds, Set<Long> pageIds) {
        RoleDto dto = new RoleDto();
        dto.setCode("IGNORED");
        dto.setDescription(description);
        dto.setPermissionIds(new HashSet<>(permissionIds));
        dto.setPageIds(new HashSet<>(pageIds));
        return dto;
    }
}
