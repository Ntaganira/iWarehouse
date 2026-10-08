package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.UserDto;
import com.ntaganira.heritier.iWarehouse.entity.Role;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.RoleRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.security.UserSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Lock-out guards and role-change logging of UserService. No database, no transaction (AfterCommit runs at once). */
class UserServiceTest {

    private UserRepository userRepo;
    private RoleRepository roleRepo;
    private ActivityLogService activityLog;
    private UserSessions sessions;
    private UserService service;

    private final Role admin = role(1L, "ADMIN");
    private final Role cashier = role(2L, "CASHIER");

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        roleRepo = mock(RoleRepository.class);
        activityLog = mock(ActivityLogService.class);
        sessions = mock(UserSessions.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenAnswer(i -> "hash:" + i.getArgument(0));
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        service = new UserService(userRepo, roleRepo, encoder, activityLog, sessions);
    }

    @Test
    void duplicateUsernameIsReportedOnTheField() {
        when(userRepo.existsByUsernameIgnoreCase("jdoe")).thenReturn(true);

        assertThatThrownBy(() -> service.create(dto("jdoe", "password123", Set.of()), true))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("username");
                    assertThat(e.getMessageKey()).isEqualTo("user.username.taken");
                });
    }

    @Test
    void shortPasswordIsRefused() {
        assertThatThrownBy(() -> service.create(dto("jdoe", "short", Set.of()), true))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getField()).isEqualTo("password"));
        assertThat(UserService.isValidPassword("x".repeat(72))).isTrue();
        assertThat(UserService.isValidPassword("é".repeat(40))).isFalse(); // 80 bytes: over BCrypt's limit
    }

    @Test
    void createLogsTheRolesGiven() {
        when(roleRepo.findAllById(Set.of(2L))).thenReturn(List.of(cashier));

        User user = service.create(dto("jdoe", "password123", Set.of(2L)), true);

        assertThat(user.getRoles()).containsExactly(cashier);
        verify(activityLog).record(eq(UserService.MODULE), eq("UPDATE_USER_ROLES"),
                eq("User jdoe: roles added [CASHIER]"), eq(ActivityStatus.SUCCESS));
    }

    @Test
    void rolesAreIgnoredWithoutAssignPermission() {
        User user = service.create(dto("jdoe", "password123", Set.of(2L)), false);

        assertThat(user.getRoles()).isEmpty();
        verifyNoInteractions(roleRepo, activityLog);
    }

    @Test
    void nobodyDisablesThemselves() {
        when(userRepo.findById(5L)).thenReturn(Optional.of(user(5L, admin)));

        assertThatThrownBy(() -> service.setEnabled(5L, false, 5L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("user.disable.self"));
    }

    @Test
    void lastActiveAdministratorStays() {
        when(userRepo.findById(5L)).thenReturn(Optional.of(user(5L, admin)));
        when(userRepo.countEnabledWithRoleExcept("ADMIN", 5L)).thenReturn(0L);

        assertThatThrownBy(() -> service.setEnabled(5L, false, 9L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("user.lastAdmin"));
        verifyNoInteractions(sessions);
    }

    @Test
    void disablingEndsTheUsersSessions() {
        User target = user(5L, cashier);
        when(userRepo.findById(5L)).thenReturn(Optional.of(target));

        service.setEnabled(5L, false, 9L);

        assertThat(target.isEnabled()).isFalse();
        verify(sessions).expire(5L);
    }

    @Test
    void ownRolesCannotBeChanged() {
        when(userRepo.findById(5L)).thenReturn(Optional.of(user(5L, admin)));
        when(roleRepo.findAllById(Set.of(2L))).thenReturn(List.of(cashier));

        assertThatThrownBy(() -> service.update(5L, dto("me", null, Set.of(2L)), true, 5L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getField()).isEqualTo("roleIds"));
    }

    @Test
    void roleChangeIsLoggedAndSessionsEnd() {
        User target = user(5L, cashier);
        when(userRepo.findById(5L)).thenReturn(Optional.of(target));
        when(roleRepo.findAllById(Set.of(1L))).thenReturn(List.of(admin));

        service.update(5L, dto("jdoe", null, Set.of(1L)), true, 9L);

        assertThat(target.getRoles()).containsExactly(admin);
        verify(activityLog).record(eq(UserService.MODULE), eq("UPDATE_USER_ROLES"),
                eq("User jdoe: roles added [ADMIN], removed [CASHIER]"), eq(ActivityStatus.SUCCESS));
        verify(sessions).expire(5L);
    }

    @Test
    void resetClearsLockoutAndEndsOtherPeoplesSessions() {
        User target = user(5L, cashier);
        target.setFailedLoginAttempts(5);
        target.setLockedUntil(java.time.LocalDateTime.now().plusMinutes(10));
        when(userRepo.findById(5L)).thenReturn(Optional.of(target));

        service.resetPassword(5L, "newPassword1", 9L);

        assertThat(target.getPassword()).isEqualTo("hash:newPassword1");
        assertThat(target.getFailedLoginAttempts()).isZero();
        assertThat(target.getLockedUntil()).isNull();
        verify(sessions).expire(5L);
    }

    private static Role role(Long id, String code) {
        return Role.builder().id(id).code(code).name("ROLE_" + code).description(code).build();
    }

    private static User user(Long id, Role... roles) {
        return User.builder().id(id).username("jdoe").email("jdoe@example.com").fullName("J Doe")
                .password("x").roles(new HashSet<>(Set.of(roles))).build();
    }

    private static UserDto dto(String username, String password, Set<Long> roleIds) {
        UserDto dto = new UserDto();
        dto.setUsername(username);
        dto.setFullName("J Doe");
        dto.setEmail(username + "@example.com");
        dto.setPassword(password);
        dto.setRoleIds(new HashSet<>(roleIds));
        return dto;
    }
}
