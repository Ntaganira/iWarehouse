package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.PasswordChangeDto;
import com.ntaganira.heritier.iWarehouse.dto.ProfileDto;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ProfileService.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : My profile: the signed-in user's own details, photo and password. Always the caller's own account
 *               (the id comes from the session); the user is audited like any change made by an administrator.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ProfileService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorage;

    public ProfileService(UserRepository userRepo, PasswordEncoder passwordEncoder, FileStorageService fileStorage) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.fileStorage = fileStorage;
    }

    public User me(Long userId) {
        return userRepo.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    }

    /** Name, email, phone and job title. An email is used by one user only, as when an administrator sets it. */
    @Transactional
    public User updateDetails(Long userId, ProfileDto dto) {
        User user = me(userId);
        String email = dto.getEmail().trim();
        if (userRepo.existsByEmailIgnoreCaseAndIdNot(email, userId)) {
            throw BusinessException.onField("email", "user.email.taken", email);
        }
        user.setFullName(dto.getFullName().trim());
        user.setEmail(email);
        user.setPhone(blankToNull(dto.getPhone()));
        user.setJobTitle(blankToNull(dto.getJobTitle()));
        return user;
    }

    /** The current password first; the new one follows the users' rule, is typed twice and is not the current one. */
    @Transactional
    public User changePassword(Long userId, PasswordChangeDto dto) {
        User user = me(userId);
        if (dto.getCurrentPassword() == null || !passwordEncoder.matches(dto.getCurrentPassword(), user.getPassword())) {
            throw BusinessException.onField("currentPassword", "profile.password.wrong");
        }
        if (!UserService.isValidPassword(dto.getNewPassword())) {
            throw BusinessException.onField("newPassword", "user.password.size", UserService.PASSWORD_MIN_CHARS);
        }
        if (!dto.getNewPassword().equals(dto.getConfirmPassword())) {
            throw BusinessException.onField("confirmPassword", "profile.password.mismatch");
        }
        if (passwordEncoder.matches(dto.getNewPassword(), user.getPassword())) {
            throw BusinessException.onField("newPassword", "profile.password.same");
        }
        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        return user;
    }

    /**
     * A new photo, squared and drawn again by {@link Avatars} before it is stored, so no file is kept as sent. It is stored
     * under a new key (users.photo_url); the previous file stays in storage, like a removed document.
     */
    @Transactional
    public User setPhoto(Long userId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw BusinessException.of("profile.photo.empty");
        }
        if (bytes.length > Documents.MAX_BYTES) {
            throw BusinessException.of("profile.photo.tooBig", Documents.MAX_BYTES / (1024 * 1024));
        }
        byte[] jpeg = Avatars.square(bytes);
        User user = me(userId);
        String key = "avatars/" + userId + "/" + UUID.randomUUID() + ".jpg";
        fileStorage.put(key, jpeg, "image/jpeg");
        user.setPhotoUrl(key);
        return user;
    }

    /** Back to the initials; the file stays in storage. */
    @Transactional
    public User removePhoto(Long userId) {
        User user = me(userId);
        user.setPhotoUrl(null);
        return user;
    }

    /** The stored photo; the key is the signed-in user's own (from the session), never one sent by the browser. */
    public InputStream photo(String key) {
        return fileStorage.open(key);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
