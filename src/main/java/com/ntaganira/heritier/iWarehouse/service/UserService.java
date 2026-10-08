package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.SetChange;
import com.ntaganira.heritier.iWarehouse.dto.UserDto;
import com.ntaganira.heritier.iWarehouse.entity.Role;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.RoleRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.security.UserSessions;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : UserService.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Users (ADM-01), ported from iVura. Users are disabled, never deleted. Guards against
 *               locking the system out: nobody disables themselves or edits their own roles, and the
 *               last enabled administrator stays. Role changes go to the activity log (AUD-03).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    public static final String ADMIN_ROLE = "ADMIN";
    static final String MODULE = "User Management";
    static final int PASSWORD_MIN_CHARS = 8;
    static final int PASSWORD_MAX_BYTES = 72; // BCrypt ignores anything longer

    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final PasswordEncoder passwordEncoder;
    private final ActivityLogService activityLogService;
    private final UserSessions userSessions;

    public UserService(UserRepository userRepo, RoleRepository roleRepo, PasswordEncoder passwordEncoder,
                       ActivityLogService activityLogService, UserSessions userSessions) {
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
        this.passwordEncoder = passwordEncoder;
        this.activityLogService = activityLogService;
        this.userSessions = userSessions;
    }

    public Page<User> findPage(String search, String status, Long roleId, int page, int size) {
        Specification<User> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase() + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("username")), term),
                        cb.like(cb.lower(root.get("email")), term),
                        cb.like(cb.lower(root.get("fullName")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("phone"), "")), term)));
            }
            if ("active".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.isTrue(root.get("enabled")));
            } else if ("inactive".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.isFalse(root.get("enabled")));
            }
            if (roleId != null) {
                query.distinct(true);
                p = cb.and(p, cb.equal(root.join("roles").get("id"), roleId));
            }
            return p;
        };
        return userRepo.findAll(spec, PageRequest.of(page, size, Sort.by("fullName").and(Sort.by("id"))));
    }

    public User findById(Long id) {
        return userRepo.findById(id).orElseThrow(() -> new NotFoundException("User", id));
    }

    public List<User> findByRole(Long roleId) {
        return userRepo.findByRoles_IdOrderByFullNameAsc(roleId);
    }

    /** New users are enabled. Roles are only taken when the caller may assign roles. */
    @Transactional
    public User create(UserDto dto, boolean assignRoles) {
        String username = dto.getUsername().trim();
        String email = dto.getEmail().trim();
        if (userRepo.existsByUsernameIgnoreCase(username)) {
            throw BusinessException.onField("username", "user.username.taken", username);
        }
        if (userRepo.existsByEmailIgnoreCase(email)) {
            throw BusinessException.onField("email", "user.email.taken", email);
        }
        if (!isValidPassword(dto.getPassword())) {
            throw BusinessException.onField("password", "user.password.size", PASSWORD_MIN_CHARS);
        }
        Set<Role> roles = assignRoles ? resolveRoles(dto.getRoleIds()) : new HashSet<>();
        User user = userRepo.save(User.builder()
                .username(username)
                .email(email)
                .fullName(dto.getFullName().trim())
                .phone(blankToNull(dto.getPhone()))
                .password(passwordEncoder.encode(dto.getPassword()))
                .roles(roles)
                .build());
        logRoleChange(user, SetChange.of(Set.of(), codes(roles)));
        return user;
    }

    /** Edits contact details and, when the caller may assign roles, the user's roles. */
    @Transactional
    public User update(Long id, UserDto dto, boolean assignRoles, Long actingUserId) {
        User user = findById(id);
        String email = dto.getEmail().trim();
        if (userRepo.existsByEmailIgnoreCaseAndIdNot(email, id)) {
            throw BusinessException.onField("email", "user.email.taken", email);
        }
        if (assignRoles) {
            Set<Role> newRoles = resolveRoles(dto.getRoleIds());
            SetChange change = SetChange.of(codes(user.getRoles()), codes(newRoles));
            if (!change.isEmpty()) {
                if (id.equals(actingUserId)) {
                    throw BusinessException.onField("roleIds", "user.roles.self");
                }
                if (user.isEnabled() && change.removed().contains(ADMIN_ROLE)) {
                    ensureAnotherAdmin(id);
                }
                user.getRoles().clear();
                user.getRoles().addAll(newRoles);
                logRoleChange(user, change);
                AfterCommit.run(() -> userSessions.expire(id)); // new access applies now, not at next sign-in
            }
        }
        user.setEmail(email);
        user.setFullName(dto.getFullName().trim());
        user.setPhone(blankToNull(dto.getPhone()));
        return user;
    }

    @Transactional
    public User setEnabled(Long id, boolean enabled, Long actingUserId) {
        User user = findById(id);
        if (user.isEnabled() == enabled) {
            return user;
        }
        if (!enabled) {
            if (id.equals(actingUserId)) {
                throw BusinessException.of("user.disable.self");
            }
            if (hasRole(user, ADMIN_ROLE)) {
                ensureAnotherAdmin(id);
            }
            AfterCommit.run(() -> userSessions.expire(id));
        }
        user.setEnabled(enabled);
        return user;
    }

    /** Sets a new password and clears any lockout. Other people's sessions end. */
    @Transactional
    public User resetPassword(Long id, String newPassword, Long actingUserId) {
        if (!isValidPassword(newPassword)) {
            throw BusinessException.of("user.password.size", PASSWORD_MIN_CHARS);
        }
        User user = findById(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        if (!id.equals(actingUserId)) {
            AfterCommit.run(() -> userSessions.expire(id));
        }
        return user;
    }

    static boolean isValidPassword(String password) {
        return password != null
                && password.length() >= PASSWORD_MIN_CHARS
                && password.getBytes(StandardCharsets.UTF_8).length <= PASSWORD_MAX_BYTES;
    }

    private void ensureAnotherAdmin(Long userId) {
        if (userRepo.countEnabledWithRoleExcept(ADMIN_ROLE, userId) == 0) {
            throw BusinessException.of("user.lastAdmin");
        }
    }

    private void logRoleChange(User user, SetChange change) {
        if (change.isEmpty()) {
            return;
        }
        String description = "User " + user.getUsername() + ": " + change.describe("roles");
        AfterCommit.run(() -> activityLogService.record(MODULE, "UPDATE_USER_ROLES", description, ActivityStatus.SUCCESS));
    }

    private Set<Role> resolveRoles(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(roleRepo.findAllById(roleIds));
    }

    private static boolean hasRole(User user, String code) {
        return user.getRoles().stream().anyMatch(r -> code.equals(r.getCode()));
    }

    private static Set<String> codes(Collection<Role> roles) {
        Set<String> codes = new HashSet<>();
        roles.forEach(r -> codes.add(r.getCode()));
        return codes;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
