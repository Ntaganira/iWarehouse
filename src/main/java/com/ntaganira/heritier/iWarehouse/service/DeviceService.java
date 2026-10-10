package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.ApiDevice;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.ApiDeviceRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DeviceService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Phones of the mobile POS (NFR-10). Signing in on a phone checks the password like the web login, needs
 *               PERM_USE_MOBILE_POS and gives the phone a token of its own (32 random bytes; only its SHA-256 is kept);
 *               signing in again on the same phone replaces it. Every API request presents the token with the phone's id
 *               (X-Device-Id): a revoked token, a disabled user or another phone's id is refused, and the user's rights
 *               are read again each time, so taking a role away takes effect at once. Revoked, never deleted.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class DeviceService {

    /** How often the last request of a phone is written down. */
    static final Duration SEEN_EVERY = Duration.ofMinutes(5);
    static final String MOBILE_AUTHORITY = "PERM_USE_MOBILE_POS";
    private static final Pattern DEVICE_KEY = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiDeviceRepository repo;
    private final UserRepository userRepo;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public DeviceService(ApiDeviceRepository repo, UserRepository userRepo, UserDetailsService userDetailsService,
                         PasswordEncoder passwordEncoder, Clock clock) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** A phone signed in, and its token: shown to the phone once, never kept. */
    public record Issued(ApiDevice device, String token, AppUserPrincipal user) {
    }

    /** A request authenticated by its token: who, on which phone. */
    public record Authenticated(AppUserPrincipal user, UUID deviceId, String deviceKey) {
    }

    // ---------------------------------------------------------------- signing in and out

    @Transactional
    public Issued signIn(String username, String password, String deviceKey, String deviceName, String userAgent) {
        if (!StringUtils.hasText(deviceKey) || !DEVICE_KEY.matcher(deviceKey).matches()) {
            throw BusinessException.of("mobile.login.device");
        }
        AppUserPrincipal user = principal(username)
                .filter(u -> password != null && passwordEncoder.matches(password, u.getPassword()))
                .orElseThrow(() -> BusinessException.of("mobile.login.invalid"));
        if (!user.isEnabled()) {
            throw BusinessException.of("mobile.login.disabled");
        }
        if (!user.isAccountNonLocked()) {
            throw BusinessException.of("mobile.login.locked");
        }
        if (user.getAuthorities().stream().noneMatch(a -> MOBILE_AUTHORITY.equals(a.getAuthority()))) {
            throw BusinessException.of("mobile.login.notAllowed");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        repo.findByUser_IdAndDeviceKeyAndRevokedAtIsNull(user.getId(), deviceKey).ifPresent(old -> {
            revoke(old, now, user.getUsername(), "Signed in again on this phone");
            repo.flush();                                        // one live token per user and phone
        });
        String token = newToken();
        ApiDevice device = new ApiDevice();
        device.setUser(userRepo.getReferenceById(user.getId()));
        device.setUsername(user.getUsername());
        device.setDeviceKey(deviceKey);
        device.setName(StringUtils.hasText(deviceName) ? truncate(deviceName.trim(), 60) : "Phone");
        device.setTokenHash(hash(token));
        device.setUserAgent(truncate(userAgent, 255));
        device.setLastSeenAt(now);
        repo.save(device);
        return new Issued(device, token, user);
    }

    /** The phone signs out: its token is revoked. */
    @Transactional
    public ApiDevice signOut(UUID deviceId) {
        ApiDevice device = repo.findById(deviceId).orElseThrow(() -> new NotFoundException("ApiDevice", deviceId));
        if (!device.isRevoked()) {
            revoke(device, LocalDateTime.now(clock), AppUserPrincipal.currentUsername(), "Signed out on the phone");
        }
        return device;
    }

    /** Revokes a phone's token from the back office (a lost phone, a driver who left), with a reason. */
    @Transactional
    public ApiDevice revoke(UUID deviceId, String reason) {
        ApiDevice device = repo.findDetailedById(deviceId).orElseThrow(() -> new NotFoundException("ApiDevice", deviceId));
        if (device.isRevoked()) {
            throw BusinessException.of("device.alreadyRevoked", device.getName());
        }
        revoke(device, LocalDateTime.now(clock), AppUserPrincipal.currentUsername(), reason.trim());
        return device;
    }

    // ---------------------------------------------------------------- authenticating requests

    /**
     * The user and phone of a token, if it is live, presented by its own phone and its user still enabled. The last request
     * is written at most every {@link #SEEN_EVERY}.
     */
    @Transactional
    public Optional<Authenticated> authenticate(String token, String deviceKey) {
        if (!StringUtils.hasText(token) || !StringUtils.hasText(deviceKey)) {
            return Optional.empty();
        }
        Optional<ApiDevice> found = repo.findByTokenHash(hash(token));
        if (found.isEmpty() || found.get().isRevoked() || !found.get().getDeviceKey().equals(deviceKey)) {
            return Optional.empty();
        }
        ApiDevice device = found.get();
        Optional<AppUserPrincipal> user = principal(device.getUsername()).filter(UserDetails::isEnabled).filter(UserDetails::isAccountNonLocked);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (device.getLastSeenAt() == null || device.getLastSeenAt().isBefore(now.minus(SEEN_EVERY))) {
            device.setLastSeenAt(now);
        }
        return Optional.of(new Authenticated(user.get(), device.getId(), device.getDeviceKey()));
    }

    // ---------------------------------------------------------------- the back office

    public Page<ApiDevice> findPage(String search, String status, int page, int size) {
        Specification<ApiDevice> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("name")), term), cb.like(cb.lower(root.get("username")), term),
                        cb.like(cb.lower(root.join("user").get("fullName")), term)));
            }
            if ("live".equals(status)) {
                p = cb.and(p, cb.isNull(root.get("revokedAt")));
            } else if ("revoked".equals(status)) {
                p = cb.and(p, cb.isNotNull(root.get("revokedAt")));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    public ApiDevice findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("ApiDevice", id));
    }

    public long liveCount() {
        return repo.countByRevokedAtIsNull();
    }

    // ---------------------------------------------------------------- helpers

    private Optional<AppUserPrincipal> principal(String username) {
        if (!StringUtils.hasText(username)) {
            return Optional.empty();
        }
        try {
            return Optional.of((AppUserPrincipal) userDetailsService.loadUserByUsername(username.trim()));
        } catch (UsernameNotFoundException e) {
            return Optional.empty();
        }
    }

    private static void revoke(ApiDevice device, LocalDateTime at, String by, String reason) {
        device.setRevokedAt(at);
        device.setRevokedBy(by);
        device.setRevokeReason(reason);
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of a token, in hex: what the table keeps. */
    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
