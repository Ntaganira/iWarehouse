package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.PasswordChangeDto;
import com.ntaganira.heritier.iWarehouse.dto.ProfileDto;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** My profile: own details, own password (current one first) and the photo, redrawn before it is stored. No database. */
class ProfileServiceTest {

    private UserRepository userRepo;
    private FileStorageService storage;
    private ProfileService service;
    private User jean;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        storage = mock(FileStorageService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenAnswer(i -> "hash:" + i.getArgument(0));
        when(encoder.matches(anyString(), anyString())).thenAnswer(i -> ("hash:" + i.getArgument(0)).equals(i.getArgument(1)));
        jean = User.builder().id(7L).username("jean").email("jean@example.rw").fullName("Jean U").password("hash:OldSecret#1").build();
        when(userRepo.findById(7L)).thenReturn(Optional.of(jean));
        service = new ProfileService(userRepo, encoder, storage);
    }

    @Test
    void detailsAreTrimmedAndBlanksCleared() {
        ProfileDto dto = details(" Jean Uwase ", " jean.uwase@example.rw ", "  ", " Warehouse supervisor ");

        service.updateDetails(7L, dto);

        assertThat(jean.getFullName()).isEqualTo("Jean Uwase");
        assertThat(jean.getEmail()).isEqualTo("jean.uwase@example.rw");
        assertThat(jean.getPhone()).isNull();
        assertThat(jean.getJobTitle()).isEqualTo("Warehouse supervisor");
        assertThat(jean.getUsername()).isEqualTo("jean");
    }

    @Test
    void anotherUsersEmailIsRefusedOnTheField() {
        when(userRepo.existsByEmailIgnoreCaseAndIdNot("owner@example.rw", 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateDetails(7L, details("Jean", "owner@example.rw", null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("email");
                    assertThat(e.getMessageKey()).isEqualTo("user.email.taken");
                });
        assertThat(jean.getEmail()).isEqualTo("jean@example.rw");
    }

    @Test
    void thePasswordChangesOnlyWithTheCurrentOne() {
        assertRefused(password("Wrong#123", "NewSecret#2", "NewSecret#2"), "currentPassword", "profile.password.wrong");
        assertRefused(password("OldSecret#1", "short", "short"), "newPassword", "user.password.size");
        assertRefused(password("OldSecret#1", "NewSecret#2", "NewSecret#3"), "confirmPassword", "profile.password.mismatch");
        assertRefused(password("OldSecret#1", "OldSecret#1", "OldSecret#1"), "newPassword", "profile.password.same");
        assertThat(jean.getPassword()).isEqualTo("hash:OldSecret#1");

        service.changePassword(7L, password("OldSecret#1", "NewSecret#2", "NewSecret#2"));

        assertThat(jean.getPassword()).isEqualTo("hash:NewSecret#2");
    }

    @Test
    void aPhotoIsRedrawnAndStoredUnderANewKey() throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", png);

        service.setPhoto(7L, png.toByteArray());

        assertThat(jean.getPhotoUrl()).matches("avatars/7/[0-9a-f-]{36}\\.jpg");
        verify(storage).put(eq(jean.getPhotoUrl()), argThat(b -> "image/jpeg".equals(Documents.detect(b))), eq("image/jpeg"));

        service.removePhoto(7L);
        assertThat(jean.getPhotoUrl()).isNull();
    }

    @Test
    void anEmptyOrForeignFileIsNeverStored() {
        assertThatThrownBy(() -> service.setPhoto(7L, new byte[0]))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("profile.photo.empty"));
        assertThatThrownBy(() -> service.setPhoto(7L, "%PDF-1.7".getBytes()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("profile.photo.type"));
        verifyNoInteractions(storage);
        assertThat(jean.getPhotoUrl()).isNull();
    }

    @Test
    void theSessionsUserTakesTheNewNameAndPhotoAndKeepsItsRights() {
        AppUserPrincipal signedIn = new AppUserPrincipal(7L, "Jean U", "jean", "x", true, true, List.of(new SimpleGrantedAuthority("PAGE_STOCK")));
        signedIn.eraseCredentials(); // as after signing in

        AppUserPrincipal updated = signedIn.withProfile("Jean Uwase", "avatars/7/a.jpg");

        assertThat(updated.getId()).isEqualTo(7L);
        assertThat(updated.getUsername()).isEqualTo("jean");
        assertThat(updated.getFullName()).isEqualTo("Jean Uwase");
        assertThat(updated.getPhotoKey()).isEqualTo("avatars/7/a.jpg");
        assertThat(updated.getAuthorities()).extracting(Object::toString).containsExactly("PAGE_STOCK");
    }

    private void assertRefused(PasswordChangeDto dto, String field, String key) {
        assertThatThrownBy(() -> service.changePassword(7L, dto))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo(field);
                    assertThat(e.getMessageKey()).isEqualTo(key);
                });
    }

    private static ProfileDto details(String name, String email, String phone, String jobTitle) {
        ProfileDto dto = new ProfileDto();
        dto.setFullName(name);
        dto.setEmail(email);
        dto.setPhone(phone);
        dto.setJobTitle(jobTitle);
        return dto;
    }

    private static PasswordChangeDto password(String current, String next, String again) {
        PasswordChangeDto dto = new PasswordChangeDto();
        dto.setCurrentPassword(current);
        dto.setNewPassword(next);
        dto.setConfirmPassword(again);
        return dto;
    }
}
