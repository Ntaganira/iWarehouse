package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.ApiDevice;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.ApiDeviceRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DeviceServiceTest.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Per-device, revocable tokens of the mobile POS (NFR-10): a driver signs in with their password and the
 *               mobile permission, the phone gets a token kept only as its SHA-256, signing in again on the phone replaces
 *               it, and a token is refused once revoked, presented by another phone or when its user is disabled.
 * </pre>
 */
class DeviceServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final String PHONE = "4f1c2a9e-77b3-4c4f-9a2b-1c0e5d6f7a8b";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final List<ApiDevice> devices = new ArrayList<>();
    private final Map<String, AppUserPrincipal> users = new HashMap<>();
    private DeviceService service;

    @BeforeEach
    void setUp() {
        ApiDeviceRepository repo = mock(ApiDeviceRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        UserDetailsService details = mock(UserDetailsService.class);
        when(details.loadUserByUsername(anyString())).thenAnswer(a -> {
            AppUserPrincipal p = users.get(a.<String>getArgument(0));
            if (p == null) throw new UsernameNotFoundException("none");
            return p;
        });
        when(userRepo.getReferenceById(any())).thenAnswer(a -> User.builder().id(a.getArgument(0)).build());
        when(repo.save(any())).thenAnswer(a -> {
            ApiDevice d = a.getArgument(0);
            d.setId(UUID.randomUUID());
            devices.add(d);
            return d;
        });
        when(repo.findByTokenHash(anyString())).thenAnswer(a -> devices.stream().filter(d -> d.getTokenHash().equals(a.getArgument(0))).findFirst());
        when(repo.findByUser_IdAndDeviceKeyAndRevokedAtIsNull(any(), anyString())).thenAnswer(a -> devices.stream()
                .filter(d -> d.getUsername().equals("driver-m7") && d.getDeviceKey().equals(a.getArgument(1)) && !d.isRevoked()).findFirst());
        when(repo.findDetailedById(any())).thenAnswer(a -> devices.stream().filter(d -> d.getId().equals(a.getArgument(0))).findFirst());
        when(repo.findById(any())).thenAnswer(a -> devices.stream().filter(d -> d.getId().equals(a.getArgument(0))).findFirst());
        users.put("driver-m7", principal("driver-m7", true, "PERM_USE_MOBILE_POS"));
        users.put("cashier1", principal("cashier1", true, "PERM_SELL"));
        service = new DeviceService(repo, userRepo, details, encoder, CLOCK);
    }

    @Test
    void aDriverGetsATokenOfTheirOwnKeptOnlyAsItsHash() {
        DeviceService.Issued issued = service.signIn("driver-m7", "unit-test-secret", PHONE, "Jean's phone", "Android");
        assertThat(issued.token()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(issued.device().getTokenHash()).hasSize(64).isEqualTo(DeviceService.hash(issued.token())).isNotEqualTo(issued.token());
        assertThat(issued.device().getName()).isEqualTo("Jean's phone");
        assertThat(issued.device().getDeviceKey()).isEqualTo(PHONE);

        assertThat(service.authenticate(issued.token(), PHONE)).get().satisfies(a -> {
            assertThat(a.user().getUsername()).isEqualTo("driver-m7");
            assertThat(a.deviceId()).isEqualTo(issued.device().getId());
        });
        // Another phone presenting the token, or a wrong token, gets nothing
        assertThat(service.authenticate(issued.token(), "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")).isEmpty();
        assertThat(service.authenticate("forged", PHONE)).isEmpty();
        assertThat(service.authenticate(null, PHONE)).isEmpty();
    }

    @Test
    void signInNeedsThePasswordAndTheMobilePermission() {
        assertThatThrownBy(() -> service.signIn("driver-m7", "wrong", PHONE, null, null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "mobile.login.invalid");
        assertThatThrownBy(() -> service.signIn("nobody", "x", PHONE, null, null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "mobile.login.invalid");
        assertThatThrownBy(() -> service.signIn("cashier1", "unit-test-secret", PHONE, null, null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "mobile.login.notAllowed");
        assertThatThrownBy(() -> service.signIn("driver-m7", "unit-test-secret", "bad key!", null, null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "mobile.login.device");
        users.put("driver-m7", principal("driver-m7", false, "PERM_USE_MOBILE_POS"));
        assertThatThrownBy(() -> service.signIn("driver-m7", "unit-test-secret", PHONE, null, null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "mobile.login.disabled");
        assertThat(devices).isEmpty();
    }

    @Test
    void signingInAgainOnThePhoneReplacesItsToken() {
        DeviceService.Issued first = service.signIn("driver-m7", "unit-test-secret", PHONE, null, null);
        DeviceService.Issued second = service.signIn("driver-m7", "unit-test-secret", PHONE, null, null);
        assertThat(first.device().isRevoked()).isTrue();
        assertThat(first.device().getRevokeReason()).isEqualTo("Signed in again on this phone");
        assertThat(second.device().getName()).isEqualTo("Phone");
        assertThat(service.authenticate(first.token(), PHONE)).isEmpty();
        assertThat(service.authenticate(second.token(), PHONE)).isPresent();
    }

    @Test
    void aRevokedPhoneOrADisabledUserIsRefusedAtOnce() {
        DeviceService.Issued issued = service.signIn("driver-m7", "unit-test-secret", PHONE, null, null);
        ApiDevice revoked = service.revoke(issued.device().getId(), "Phone lost on the road");
        assertThat(revoked.getRevokeReason()).isEqualTo("Phone lost on the road");
        assertThat(service.authenticate(issued.token(), PHONE)).isEmpty();
        assertThatThrownBy(() -> service.revoke(issued.device().getId(), "again"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "device.alreadyRevoked");

        DeviceService.Issued fresh = service.signIn("driver-m7", "unit-test-secret", PHONE, null, null);
        users.put("driver-m7", principal("driver-m7", false, "PERM_USE_MOBILE_POS"));
        assertThat(service.authenticate(fresh.token(), PHONE)).isEmpty();
    }

    private AppUserPrincipal principal(String username, boolean enabled, String authority) {
        return new AppUserPrincipal(username.equals("driver-m7") ? 21L : 22L, "Jean Driver", username, encoder.encode("unit-test-secret"),
                enabled, true, List.of(new SimpleGrantedAuthority(authority)));
    }
}
